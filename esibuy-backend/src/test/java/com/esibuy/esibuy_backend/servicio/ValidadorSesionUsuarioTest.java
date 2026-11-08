package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.esibuy.esibuy_backend.modelo.PerfilUsuario;
import com.esibuy.esibuy_backend.modelo.Rol;
import com.esibuy.esibuy_backend.modelo.Usuario;
import com.esibuy.esibuy_backend.repositorio.RepositorioUsuario;

/**
 * Cuando una sesion deja de valer para su usuario (HdU recuperacion de contrasena, fase 4).
 * Casos: CP-RST-30 a CP-RST-37.
 *
 * Una sesion deja de valer si su usuario cambio la contrasena (por recuperacion) a partir del momento en que se inicio
 * la sesion, o si el usuario ya no existe. Es la pieza que hace efectivo el "se invalidan todas las sesiones abiertas":
 * las sesiones viven en la memoria de Tomcat y no se pueden listar ni cerrar desde fuera, asi que cada peticion
 * autenticada comprueba la marca contrasenaCambiadaEn del usuario.
 *
 * Reglas finas que fijan:
 *  - Mongo guarda las fechas en milisegundos y la sesion guarda el instante con mas precision: se comparan truncados a
 *    milisegundos, para que una sesion iniciada en el mismo milisegundo en que se cambio la contrasena (o antes, dentro
 *    de ese milisegundo) no sobreviva por un redondeo.
 *  - Una sesion iniciada en el instante exacto del cambio (o antes) NO vale: se falla cerrado. Solo vale la que se
 *    inicia estrictamente despues.
 *  - Sin id de usuario o sin instante de inicio, no vale (y no se consulta nada).
 *  - Un fallo de la base de datos se propaga: lo decide el filtro (503), este componente no lo disimula.
 *
 * Terminada: ya no lleva la etiqueta pendiente-recuperacion y entra en la regresion.
 */
@ExtendWith(MockitoExtension.class)
class ValidadorSesionUsuarioTest {

    private static final String ID = "665f1c2e9b1e8a3d4c5b6a79";
    private static final Instant CAMBIO = Instant.parse("2026-10-19T10:00:00.000Z");

    @Mock
    private RepositorioUsuario repositorioUsuario;

    private ValidadorSesionUsuario validador;

    @BeforeEach
    void prepararValidador() {
        validador = new ValidadorSesionUsuario(repositorioUsuario);
    }

    private static Usuario usuarioQueCambioLaContrasenaEn(Instant cambio) {
        Usuario usuario = new Usuario("ana@ejemplo.es", "hash", Rol.CLIENTE,
                PerfilUsuario.builder().nombre("Ana").build());
        ReflectionTestUtils.setField(usuario, "id", ID);
        ReflectionTestUtils.setField(usuario, "contrasenaCambiadaEn", cambio);
        return usuario;
    }

    private void dadoUsuarioQueCambioLaContrasenaEn(Instant cambio) {
        when(repositorioUsuario.buscarMarcaDeCredenciales(ID)).thenReturn(Optional.of(usuarioQueCambioLaContrasenaEn(cambio)));
    }

    @Test
    void sigueVigente_usuarioQueNuncaCambioLaContrasena_vale() { // CP-RST-30
        dadoUsuarioQueCambioLaContrasenaEn(null);

        assertThat(validador.sigueVigente(ID, Instant.parse("2026-10-01T10:00:00Z"))).isTrue();
    }

    @Test
    void sigueVigente_sesionIniciadaDespuesDelCambio_vale() { // CP-RST-31
        dadoUsuarioQueCambioLaContrasenaEn(CAMBIO);

        assertThat(validador.sigueVigente(ID, CAMBIO.plusSeconds(1))).isTrue();
    }

    @Test
    void sigueVigente_sesionIniciadaAntesDelCambio_noVale() { // CP-RST-32
        dadoUsuarioQueCambioLaContrasenaEn(CAMBIO);

        assertThat(validador.sigueVigente(ID, CAMBIO.minusSeconds(1))).isFalse();
        assertThat(validador.sigueVigente(ID, CAMBIO.minusSeconds(3600))).isFalse();
    }

    @Test
    void sigueVigente_sesionIniciadaEnElInstanteExactoDelCambio_noVale() { // CP-RST-33
        dadoUsuarioQueCambioLaContrasenaEn(CAMBIO);

        assertThat(validador.sigueVigente(ID, CAMBIO)).isFalse();
    }

    @Test
    void sigueVigente_laSesionTieneMasPrecisionQueMongo_seCompruebaTruncadoAMilisegundos() { // CP-RST-34
        // Given: Mongo guarda el cambio como 10:00:00.000; la sesion se inicio 0,5 ms despues (mismo milisegundo)
        dadoUsuarioQueCambioLaContrasenaEn(CAMBIO);

        // Then: sin truncar, la sesion parecerian posterior y sobreviviria al cambio
        assertThat(validador.sigueVigente(ID, CAMBIO.plusNanos(500_000))).isFalse();
        // y una iniciada en el milisegundo siguiente, si vale
        assertThat(validador.sigueVigente(ID, CAMBIO.plusMillis(1))).isTrue();
    }

    @Test
    void sigueVigente_elUsuarioYaNoExiste_noVale() { // CP-RST-35
        when(repositorioUsuario.buscarMarcaDeCredenciales(ID)).thenReturn(Optional.empty());

        assertThat(validador.sigueVigente(ID, CAMBIO.plusSeconds(1))).isFalse();
    }

    @Test
    void sigueVigente_sinIdOSinInstanteDeInicio_noValeYNoConsultaNada() { // CP-RST-36
        assertThat(validador.sigueVigente(null, CAMBIO)).isFalse();
        assertThat(validador.sigueVigente("", CAMBIO)).isFalse();
        assertThat(validador.sigueVigente(ID, null)).isFalse();

        verifyNoInteractions(repositorioUsuario);
    }

    @Test
    void sigueVigente_fallaLaBaseDeDatos_lanzaLaExcepcionSinDisimularla() { // CP-RST-37
        when(repositorioUsuario.buscarMarcaDeCredenciales(anyString())).thenThrow(new IllegalStateException("mongo caido"));

        assertThrows(IllegalStateException.class, () -> validador.sigueVigente(ID, CAMBIO));
        verify(repositorioUsuario).buscarMarcaDeCredenciales(ID);
    }
}
