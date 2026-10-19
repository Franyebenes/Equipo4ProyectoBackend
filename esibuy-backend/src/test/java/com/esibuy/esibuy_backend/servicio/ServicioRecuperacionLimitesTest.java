package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.esibuy.esibuy_backend.dto.SolicitudRecuperacionDTO;
import com.esibuy.esibuy_backend.excepcion.DatosRecuperacionInvalidosException;
import com.esibuy.esibuy_backend.modelo.EstadoUsuario;
import com.esibuy.esibuy_backend.modelo.PerfilUsuario;
import com.esibuy.esibuy_backend.modelo.Rol;
import com.esibuy.esibuy_backend.modelo.Usuario;
import com.esibuy.esibuy_backend.repositorio.RepositorioUsuario;
import com.esibuy.esibuy_backend.util.PropiedadesCorreoPrueba;

/**
 * Como usa ServicioRecuperacion el limitador (HdU recuperacion de contrasena, fase 3).
 * Casos: CP-LIM-40 a CP-LIM-49. Complementa ServicioRecuperacionTest (fase 2).
 *
 * El limitador va mockeado para fijar solo el orden y las condiciones en las que se consulta:
 *  - La IP se comprueba la primera, antes de buscar la cuenta: una IP agotada no genera mas trabajo.
 *  - La cuenta solo cuenta (y solo se consulta) si es ACTIVO o DESACTIVADO, que son las que pueden recibir un correo.
 *    Las bloqueadas y los correos que no existen solo consumen cupo de IP.
 *  - Una solicitud limitada no emite token ni manda correo, queda auditada con su motivo, y el metodo termina igual
 *    que cualquier otra (sin excepcion): la respuesta al cliente sigue siendo la generica.
 *  - Un correo mal formado (400) no consume cupo.
 *
 * Terminada: ya no lleva la etiqueta pendiente-recuperacion y entra en la regresion.
 */
@ExtendWith(MockitoExtension.class)
class ServicioRecuperacionLimitesTest {

    private static final String ID_USUARIO = "665f1c2e9b1e8a3d4c5b6a79";
    private static final String EMAIL = "ana.garcia@ejemplo.es";
    private static final String IP = "203.0.113.7";
    private static final ContextoPeticion CONTEXTO = new ContextoPeticion(IP, "JUnit");

    @Mock
    private RepositorioUsuario repositorioUsuario;
    @Mock
    private ServicioTokensRecuperacion tokens;
    @Mock
    private AuditoriaSeguridad auditoria;
    @Mock
    private LimitadorRecuperacion limitador;

    private final List<MensajeCorreo> enviados = new ArrayList<>();
    private ServicioRecuperacion servicio;

    @BeforeEach
    void prepararServicio() {
        PlantillasCorreo plantillas = new PlantillasCorreo(PropiedadesCorreoPrueba.remoto().construir());
        NotificadorCorreo notificador = new NotificadorCorreo(enviados::add, Runnable::run, auditoria);
        servicio = new ServicioRecuperacion(repositorioUsuario, tokens, plantillas, notificador, auditoria, limitador);
    }

    private static Usuario usuario(EstadoUsuario estado) {
        Usuario usuario = new Usuario(EMAIL, "hash", Rol.CLIENTE, PerfilUsuario.builder().nombre("Ana").build());
        ReflectionTestUtils.setField(usuario, "id", ID_USUARIO);
        ReflectionTestUtils.setField(usuario, "estado", estado);
        return usuario;
    }

    private void dadaLaCuenta(EstadoUsuario estado) {
        when(repositorioUsuario.buscarPorEmail(EMAIL)).thenReturn(Optional.of(usuario(estado)));
    }

    private void solicitar() {
        servicio.solicitar(new SolicitudRecuperacionDTO(EMAIL), CONTEXTO);
    }

    private static Optional<MotivoLimiteRecuperacion> limitada(MotivoLimiteRecuperacion motivo) {
        return Optional.of(motivo);
    }

    // ------------------------------------------------------------------ CP-LIM-40 / 41

    @Test
    void solicitar_cuentaActivaDentroDeLosLimites_consultaLaIpYLaCuentaYEnviaElCorreo() { // CP-LIM-40
        // Given
        dadaLaCuenta(EstadoUsuario.ACTIVO);
        when(tokens.emitir(ID_USUARIO)).thenReturn(new TokenEmitido("Zk3-Qw9_Lm2xPa7RtYb4NcV8HdS1JfGe6UoIi0KuXyA",
                java.time.Instant.parse("2026-10-17T10:00:00Z"), java.time.Instant.parse("2026-10-17T10:15:00Z")));

        // When
        solicitar();

        // Then: la IP primero (antes de buscar la cuenta) y la cuenta con el correo normalizado
        InOrder orden = inOrder(limitador, repositorioUsuario);
        orden.verify(limitador).registrarSolicitudDeIp(IP);
        orden.verify(repositorioUsuario).buscarPorEmail(EMAIL);
        orden.verify(limitador).registrarSolicitudDeCuenta(EMAIL);
        assertThat(enviados).hasSize(1);
        verify(auditoria, never()).registrarRecuperacionLimitada(org.mockito.ArgumentMatchers.any(),
                anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void solicitar_correoConEspaciosYMayusculas_elLimitadorRecibeElCorreoNormalizado() { // CP-LIM-41
        dadaLaCuenta(EstadoUsuario.DESACTIVADO);

        servicio.solicitar(new SolicitudRecuperacionDTO("  Ana.Garcia@EJEMPLO.es "), CONTEXTO);

        // Si no se normalizara, "ANA@..." y "ana@..." tendrian contadores distintos y se esquivaria el limite
        verify(limitador).registrarSolicitudDeCuenta(EMAIL);
    }

    // ------------------------------------------------------------------ CP-LIM-42

    @Test
    void solicitar_ipAgotada_noBuscaLaCuentaNiEnviaNadaYSeAudita() { // CP-LIM-42
        // Given
        when(limitador.registrarSolicitudDeIp(IP)).thenReturn(limitada(MotivoLimiteRecuperacion.IP_AGOTADA));

        // When / Then: termina sin error, como cualquier otra solicitud
        assertDoesNotThrow(this::solicitar);
        verifyNoInteractions(repositorioUsuario, tokens);
        verify(limitador, never()).registrarSolicitudDeCuenta(anyString());
        assertThat(enviados).isEmpty();
        verify(auditoria).registrarRecuperacionLimitada(CONTEXTO, EMAIL, MotivoLimiteRecuperacion.IP_AGOTADA);
    }

    // ------------------------------------------------------------------ CP-LIM-43 / 44

    @ParameterizedTest(name = "cuenta {0} limitada por {1}")
    @EnumSource(value = EstadoUsuario.class, names = { "ACTIVO", "DESACTIVADO" })
    void solicitar_cuentaLimitada_noEmiteTokenNiEnviaNadaYSeAudita(EstadoUsuario estado) { // CP-LIM-43
        // Given
        dadaLaCuenta(estado);
        when(limitador.registrarSolicitudDeCuenta(EMAIL)).thenReturn(limitada(MotivoLimiteRecuperacion.COOLDOWN));

        // When
        assertDoesNotThrow(this::solicitar);

        // Then: ni token, ni correo (ni el de enlace ni el de cuenta pendiente), y queda constancia del motivo
        verifyNoInteractions(tokens);
        assertThat(enviados).isEmpty();
        verify(auditoria).registrarRecuperacionLimitada(CONTEXTO, EMAIL, MotivoLimiteRecuperacion.COOLDOWN);
        verify(auditoria, never()).registrarRecuperacionSolicitada(org.mockito.ArgumentMatchers.any(), anyString(),
                anyString());
        verify(auditoria, never()).registrarRecuperacionCuentaNoActiva(org.mockito.ArgumentMatchers.any(), anyString());
    }

    @Test
    void solicitar_cuentaConElMaximoDiarioAgotado_seAuditaConEseMotivo() { // CP-LIM-44
        dadaLaCuenta(EstadoUsuario.ACTIVO);
        when(limitador.registrarSolicitudDeCuenta(EMAIL)).thenReturn(limitada(MotivoLimiteRecuperacion.LIMITE_DIARIO));

        solicitar();

        verify(auditoria).registrarRecuperacionLimitada(CONTEXTO, EMAIL, MotivoLimiteRecuperacion.LIMITE_DIARIO);
        assertThat(enviados).isEmpty();
    }

    // ------------------------------------------------------------------ CP-LIM-45 / 46

    @ParameterizedTest(name = "cuenta {0}")
    @EnumSource(value = EstadoUsuario.class, names = { "BLOQUEADO" })
    void solicitar_cuentaQueNoRecibeCorreo_soloConsumeCupoDeIp(EstadoUsuario estado) { // CP-LIM-45
        dadaLaCuenta(estado);

        solicitar();

        verify(limitador).registrarSolicitudDeIp(IP);
        verify(limitador, never()).registrarSolicitudDeCuenta(anyString());
    }

    @Test
    void solicitar_correoNoRegistrado_soloConsumeCupoDeIp() { // CP-LIM-46
        when(repositorioUsuario.buscarPorEmail(EMAIL)).thenReturn(Optional.empty());

        solicitar();

        verify(limitador).registrarSolicitudDeIp(IP);
        verify(limitador, never()).registrarSolicitudDeCuenta(anyString());
    }

    // ------------------------------------------------------------------ CP-LIM-47

    @Test
    void solicitar_correoMalFormado_noConsumeCupo() { // CP-LIM-47
        assertThrows(DatosRecuperacionInvalidosException.class,
                () -> servicio.solicitar(new SolicitudRecuperacionDTO("no-es-un-correo"), CONTEXTO));

        verifyNoInteractions(limitador);
    }

    // ------------------------------------------------------------------ CP-LIM-48

    @Test
    void solicitar_unaSolicitudLimitadaYOtraQueNo_terminanDeLaMismaManera() { // CP-LIM-48
        // Given: la primera pasa y la segunda queda limitada por la espera
        dadaLaCuenta(EstadoUsuario.DESACTIVADO);
        when(limitador.registrarSolicitudDeCuenta(EMAIL)).thenReturn(Optional.empty())
                .thenReturn(limitada(MotivoLimiteRecuperacion.COOLDOWN));

        // When / Then: ninguna lanza ni devuelve nada distinto (el controlador responde 200 en las dos)
        assertDoesNotThrow(this::solicitar);
        assertDoesNotThrow(this::solicitar);
        assertThat(enviados).hasSize(1);
    }
}
