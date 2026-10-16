package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.esibuy.esibuy_backend.dto.SolicitudRecuperacionDTO;
import com.esibuy.esibuy_backend.excepcion.DatosRecuperacionInvalidosException;
import com.esibuy.esibuy_backend.excepcion.FalloEnvioCorreoException;
import com.esibuy.esibuy_backend.excepcion.ServicioNoDisponibleException;
import com.esibuy.esibuy_backend.modelo.EstadoUsuario;
import com.esibuy.esibuy_backend.modelo.PerfilUsuario;
import com.esibuy.esibuy_backend.modelo.Rol;
import com.esibuy.esibuy_backend.modelo.Usuario;
import com.esibuy.esibuy_backend.repositorio.RepositorioUsuario;
import com.esibuy.esibuy_backend.util.CapturadorLogs;
import com.esibuy.esibuy_backend.util.PropiedadesCorreoPrueba;

/**
 * Logica de la solicitud de recuperacion de contrasena (HdU recuperacion de contrasena, fase 2).
 * Casos: CP-REC-01 a CP-REC-13.
 *
 * Colaboradores: repositorio y servicio de tokens mockeados; plantillas, NotificadorCorreo y auditoria reales (el
 * enviador es un grabador). Por defecto el ejecutor corre las tareas en el mismo hilo para poder comprobar sin
 * esperas; el test del segundo plano usa una cola manual.
 *
 * Reglas que fijan (decididas con el equipo):
 *  - ACTIVO: se emite un token y se manda el enlace de recuperacion al correo de la cuenta.
 *  - DESACTIVADO (pendiente de activacion): no se emite token; se manda el aviso de cuenta pendiente.
 *  - BLOQUEADO y correo no registrado: no se manda nada.
 *  - Hacia fuera el metodo es igual en todos los casos (devuelve sin mas): la respuesta generica es cosa del controlador.
 *  - Lo que depende del estado de la cuenta (emitir el token, preparar y enviar el correo) ocurre en segundo plano, para
 *    que el tiempo de respuesta no delate si el correo esta registrado. En la peticion solo se valida, se busca la
 *    cuenta (una consulta, exista o no) y se audita.
 *  - Un correo mal formado es un 400 que depende solo del texto, nunca de la cuenta.
 *
 * Terminada: ya no lleva la etiqueta pendiente-recuperacion y entra en la regresion.
 */
@ExtendWith(MockitoExtension.class)
class ServicioRecuperacionTest {

    private static final String ID_USUARIO = "665f1c2e9b1e8a3d4c5b6a79";
    private static final String EMAIL = "ana.garcia@ejemplo.es";
    private static final String TOKEN = "Zk3-Qw9_Lm2xPa7RtYb4NcV8HdS1JfGe6UoIi0KuXyA";
    private static final ContextoPeticion CONTEXTO = new ContextoPeticion("203.0.113.7", "JUnit");
    private static final Instant AHORA = Instant.parse("2026-10-16T10:00:00Z");

    @Mock
    private RepositorioUsuario repositorioUsuario;
    @Mock
    private ServicioTokensRecuperacion tokens;
    @Mock
    private AuditoriaSeguridad auditoria;

    private final List<MensajeCorreo> enviados = new ArrayList<>();
    private final List<Runnable> tareasPendientes = new ArrayList<>();
    private final PlantillasCorreo plantillas = new PlantillasCorreo(PropiedadesCorreoPrueba.remoto().construir());

    private EnviadorCorreo enviador = enviados::add;
    private Executor ejecutor = Runnable::run;
    private ServicioRecuperacion servicio;

    @BeforeEach
    void prepararServicio() {
        servicio = crearServicio(auditoria);
    }

    private ServicioRecuperacion crearServicio(AuditoriaSeguridad auditoriaUsada) {
        // El enviador y el ejecutor se consultan en cada uso: un test puede sustituirlos despues de crear el servicio
        NotificadorCorreo notificador = new NotificadorCorreo(mensaje -> enviador.enviar(mensaje), tarea -> ejecutor.execute(tarea),
                auditoriaUsada);
        return new ServicioRecuperacion(repositorioUsuario, tokens, plantillas, notificador, auditoriaUsada);
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

    private void dadoQueSeEmiteElToken() {
        when(tokens.emitir(ID_USUARIO))
                .thenReturn(new TokenEmitido(TOKEN, AHORA, AHORA.plus(ServicioTokensRecuperacion.VALIDEZ)));
    }

    // ------------------------------------------------------------------ CP-REC-01 a 04: segun el estado

    @Test
    void solicitar_cuentaActiva_emiteUnTokenYEnviaElEnlaceAlCorreoDeLaCuenta() { // CP-REC-01
        // Given
        dadaLaCuenta(EstadoUsuario.ACTIVO);
        dadoQueSeEmiteElToken();

        // When
        servicio.solicitar(new SolicitudRecuperacionDTO(EMAIL), CONTEXTO);

        // Then: un solo correo, al correo de la cuenta, con el enlace que lleva el token
        verify(tokens).emitir(ID_USUARIO);
        assertThat(enviados).singleElement().satisfies(correo -> {
            assertThat(correo.destinatario()).isEqualTo(EMAIL);
            assertThat(correo.cuerpo()).contains("https://esibuy.es/restablecer-contrasena#token=" + TOKEN);
            assertThat(correo.cuerpo()).containsIgnoringCase("15 minutos");
        });
        verify(auditoria).registrarRecuperacionSolicitada(CONTEXTO, EMAIL, ID_USUARIO);
    }

    @Test
    void solicitar_cuentaPendienteDeActivacion_enviaElAvisoSinEmitirToken() { // CP-REC-02
        // Given
        dadaLaCuenta(EstadoUsuario.DESACTIVADO);

        // When
        servicio.solicitar(new SolicitudRecuperacionDTO(EMAIL), CONTEXTO);

        // Then: el aviso de cuenta pendiente, sin enlace, y no se crea ningun token
        verifyNoInteractions(tokens);
        assertThat(enviados).singleElement().satisfies(correo -> {
            assertThat(correo.destinatario()).isEqualTo(EMAIL);
            assertThat(correo.cuerpo()).containsIgnoringCase("pendiente de activación");
            assertThat(correo.cuerpo()).doesNotContainIgnoringCase("http");
        });
        verify(auditoria).registrarRecuperacionCuentaNoActiva(CONTEXTO, EMAIL);
    }

    @Test
    void solicitar_cuentaBloqueada_noEnviaNadaNiEmiteToken() { // CP-REC-03
        // Given
        dadaLaCuenta(EstadoUsuario.BLOQUEADO);

        // When
        servicio.solicitar(new SolicitudRecuperacionDTO(EMAIL), CONTEXTO);

        // Then
        verifyNoInteractions(tokens);
        assertThat(enviados).isEmpty();
        verify(auditoria).registrarRecuperacionCuentaNoApta(CONTEXTO, EMAIL, EstadoUsuario.BLOQUEADO);
    }

    @Test
    void solicitar_correoNoRegistrado_noEnviaNadaNiEmiteToken() { // CP-REC-04
        // Given
        when(repositorioUsuario.buscarPorEmail(EMAIL)).thenReturn(Optional.empty());

        // When
        servicio.solicitar(new SolicitudRecuperacionDTO(EMAIL), CONTEXTO);

        // Then
        verifyNoInteractions(tokens);
        assertThat(enviados).isEmpty();
        verify(auditoria).registrarRecuperacionEmailDesconocido(CONTEXTO, EMAIL);
    }

    // ------------------------------------------------------------------ CP-REC-05

    @Test
    void solicitar_correoConEspaciosYMayusculas_seBuscaNormalizado() { // CP-REC-05
        // Given: el correo se guarda en minusculas y sin espacios, igual que en el login
        dadaLaCuenta(EstadoUsuario.DESACTIVADO);

        // When
        servicio.solicitar(new SolicitudRecuperacionDTO("  Ana.Garcia@EJEMPLO.es "), CONTEXTO);

        // Then
        verify(repositorioUsuario).buscarPorEmail(EMAIL);
        assertThat(enviados).singleElement().satisfies(correo -> assertThat(correo.destinatario()).isEqualTo(EMAIL));
    }

    // ------------------------------------------------------------------ CP-REC-06

    @ParameterizedTest(name = "correo no valido: [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = { "   ", "no-es-un-correo", "sin@arroba-ni-punto", "a@b", "ana@ejemplo.es, luis@ejemplo.es",
            "ana ejemplo@ejemplo.es", "{ \"$ne\": null }" })
    void solicitar_correoNoValido_lanza400SinConsultarNiEnviarNiAuditar(String correo) { // CP-REC-06
        // When
        DatosRecuperacionInvalidosException error = assertThrows(DatosRecuperacionInvalidosException.class,
                () -> servicio.solicitar(new SolicitudRecuperacionDTO(correo), CONTEXTO));

        // Then: error en el campo email, y nada mas ocurre
        assertThat(error.getErrores()).containsOnlyKeys("email");
        assertThat(error.getErrores().get("email")).isNotEmpty();
        assertThat(error.getMessage()).doesNotContain("ejemplo");
        verifyNoInteractions(repositorioUsuario, tokens, auditoria);
        assertThat(enviados).isEmpty();
    }

    @Test
    void solicitar_correoDemasiadoLargo_lanza400() { // CP-REC-06
        String largo = "a".repeat(250) + "@ejemplo.es";

        assertThrows(DatosRecuperacionInvalidosException.class,
                () -> servicio.solicitar(new SolicitudRecuperacionDTO(largo), CONTEXTO));
        verifyNoInteractions(repositorioUsuario);
    }

    @Test
    void solicitar_solicitudNula_lanza400() { // CP-REC-06
        assertThrows(DatosRecuperacionInvalidosException.class, () -> servicio.solicitar(null, CONTEXTO));
        verifyNoInteractions(repositorioUsuario);
    }

    // ------------------------------------------------------------------ CP-REC-07

    @Test
    void solicitar_cuentaActiva_elTokenYElCorreoSeHacenEnSegundoPlano() { // CP-REC-07
        // Given: un ejecutor que guarda las tareas sin ejecutarlas
        dadaLaCuenta(EstadoUsuario.ACTIVO);
        dadoQueSeEmiteElToken();
        ejecutor = tareasPendientes::add;

        // When
        servicio.solicitar(new SolicitudRecuperacionDTO(EMAIL), CONTEXTO);

        // Then: al volver, todavia no se ha emitido el token ni enviado nada (asi el tiempo de respuesta no depende
        // de la cuenta), pero la decision ya esta auditada
        verifyNoInteractions(tokens);
        assertThat(enviados).isEmpty();
        verify(auditoria).registrarRecuperacionSolicitada(CONTEXTO, EMAIL, ID_USUARIO);
        assertThat(tareasPendientes).isNotEmpty();

        // y al ejecutarse la tarea, ocurre todo
        tareasPendientes.forEach(Runnable::run);
        verify(tokens).emitir(ID_USUARIO);
        assertThat(enviados).hasSize(1);
    }

    // ------------------------------------------------------------------ CP-REC-08 / 09 / 10

    @Test
    void solicitar_fallaLaBaseDeDatosAlBuscarLaCuenta_falla503SinDetalles() { // CP-REC-08
        // Given
        when(repositorioUsuario.buscarPorEmail(anyString())).thenThrow(new IllegalStateException("mongo caido: usuarios"));

        // When / Then: falla cerrado, como el login
        ServicioNoDisponibleException error = assertThrows(ServicioNoDisponibleException.class,
                () -> servicio.solicitar(new SolicitudRecuperacionDTO(EMAIL), CONTEXTO));
        assertThat(error.getMessage()).doesNotContain("mongo", "usuarios", EMAIL);
        assertThat(enviados).isEmpty();
        verifyNoInteractions(tokens);
    }

    @Test
    void solicitar_fallaLaEmisionDelToken_noSePropagaNiSeEnviaYQuedaAuditado() { // CP-REC-09
        // Given
        dadaLaCuenta(EstadoUsuario.ACTIVO);
        when(tokens.emitir(ID_USUARIO)).thenThrow(new IllegalStateException("fallo de mongo"));

        // When / Then
        assertDoesNotThrow(() -> servicio.solicitar(new SolicitudRecuperacionDTO(EMAIL), CONTEXTO));
        assertThat(enviados).isEmpty();
        verify(auditoria).registrarFalloEnvioCorreo(eq(EMAIL), any(IllegalStateException.class));
    }

    @Test
    void solicitar_fallaElServidorSmtp_noSePropagaYQuedaAuditado() { // CP-REC-10
        // Given
        dadaLaCuenta(EstadoUsuario.ACTIVO);
        dadoQueSeEmiteElToken();
        enviador = mensaje -> {
            throw new FalloEnvioCorreoException("No se pudo enviar el correo", null);
        };

        // When / Then
        assertDoesNotThrow(() -> servicio.solicitar(new SolicitudRecuperacionDTO(EMAIL), CONTEXTO));
        verify(auditoria).registrarFalloEnvioCorreo(eq(EMAIL), any(FalloEnvioCorreoException.class));
    }

    // ------------------------------------------------------------------ CP-REC-11 / 12

    @Test
    void solicitar_dosSolicitudesSeguidas_emiteUnTokenPorCada() { // CP-REC-11
        // Given: ServicioTokensRecuperacion.emitir ya anula el anterior (probado en la fase 1)
        dadaLaCuenta(EstadoUsuario.ACTIVO);
        dadoQueSeEmiteElToken();

        // When
        servicio.solicitar(new SolicitudRecuperacionDTO(EMAIL), CONTEXTO);
        servicio.solicitar(new SolicitudRecuperacionDTO(EMAIL), CONTEXTO);

        // Then
        verify(tokens, org.mockito.Mockito.times(2)).emitir(ID_USUARIO);
        assertThat(enviados).hasSize(2);
    }

    @Test
    void solicitar_cuentaSinEstadoReconocible_noEnviaNada() { // CP-REC-12
        // Given: un documento sin estado (dato corrupto): falla cerrado, como si no pudiera recuperar
        when(repositorioUsuario.buscarPorEmail(EMAIL)).thenReturn(Optional.of(usuario(null)));

        // When
        assertDoesNotThrow(() -> servicio.solicitar(new SolicitudRecuperacionDTO(EMAIL), CONTEXTO));

        // Then
        verify(tokens, never()).emitir(anyString());
        assertThat(enviados).isEmpty();
    }

    // ------------------------------------------------------------------ CP-REC-13

    @Test
    void solicitar_flujoCompleto_nadaEnElLogContieneElTokenNiElEnlaceNiElCorreoEnClaro() { // CP-REC-13
        // Given: auditoria real para leer lo que de verdad se escribe en el log
        ServicioRecuperacion conAuditoriaReal = crearServicio(new AuditoriaSeguridad());
        dadaLaCuenta(EstadoUsuario.ACTIVO);
        dadoQueSeEmiteElToken();
        enviador = mensaje -> {
            throw new FalloEnvioCorreoException("No se pudo enviar el correo",
                    new IllegalStateException("535 clave incorrecta " + TOKEN));
        };

        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            // When: una solicitud correcta y, con el SMTP fallando, tambien el aviso de fallo
            conAuditoriaReal.solicitar(new SolicitudRecuperacionDTO(EMAIL), CONTEXTO);

            // Then
            assertThat(logs.textosCompletos()).isNotEmpty()
                    .noneMatch(texto -> texto.contains(TOKEN) || texto.contains("restablecer-contrasena")
                            || texto.contains(EMAIL));
        }
    }
}
