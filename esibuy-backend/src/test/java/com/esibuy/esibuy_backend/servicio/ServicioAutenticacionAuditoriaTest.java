package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

import com.esibuy.esibuy_backend.dto.SolicitudLoginDTO;
import com.esibuy.esibuy_backend.excepcion.CredencialesInvalidasException;
import com.esibuy.esibuy_backend.excepcion.DatosLoginInvalidosException;
import com.esibuy.esibuy_backend.excepcion.LoginBloqueadoTemporalmenteException;
import com.esibuy.esibuy_backend.modelo.EstadoUsuario;
import com.esibuy.esibuy_backend.modelo.Rol;
import com.esibuy.esibuy_backend.modelo.Usuario;

/**
 * Qué comunica ServicioAutenticacion a la auditoría en cada resultado del inicio de sesión.
 *
 * Orden TDD: paso 4 (se conecta sobre el paso 3).
 * Casos: CP-AUD-01 (la parte del servicio; cómo se escribe cada evento lo comprueba AuditoriaSeguridadTest).
 * Colaboradores: los de ServicioAutenticacionBaseTest (AuditoriaSeguridad mockeada).
 *
 * Clase añadida en la fase 4 porque las pruebas de la fase 3 están cerradas y no miran la auditoría.
 *
 * Desarrollada con el ciclo Red-Green-Refactor: primero el test (Arrange, Act, Assert), después el código
 * mínimo que lo hace pasar. Terminada: ya no lleva la etiqueta pendiente-login y entra en la regresión.
 */
class ServicioAutenticacionAuditoriaTest extends ServicioAutenticacionBaseTest {

    private static final String CONTRASENA_INCORRECTA = "Otra-Contrasena-99";

    @Test
    void autenticar_loginCorrecto_registraElResultadoConElCorreoNormalizadoYElIdDelUsuario() { // CP-AUD-01
        // Given
        dadoUsuarioRegistrado(usuario(Rol.CLIENTE, EstadoUsuario.ACTIVO));
        dadaContrasenaCorrecta();

        // When: el correo llega sin normalizar
        servicio.autenticar(new SolicitudLoginDTO("  ANA.Garcia@Ejemplo.ES ", CONTRASENA), CONTEXTO);

        // Then
        verify(auditoria).registrarLoginCorrecto(CONTEXTO, EMAIL, ID_USUARIO);
        verify(auditoria, never()).registrarLoginFallido(any(), any(), any());
    }

    static Stream<Arguments> rechazosYSuMotivo() {
        return Stream.of(
                arguments(MotivoFalloLogin.CORREO_NO_REGISTRADO, CredencialesInvalidasException.class),
                arguments(MotivoFalloLogin.CONTRASENA_INCORRECTA, CredencialesInvalidasException.class),
                arguments(MotivoFalloLogin.CUENTA_NO_ACTIVA, CredencialesInvalidasException.class),
                arguments(MotivoFalloLogin.ROLES_INVALIDOS, CredencialesInvalidasException.class),
                arguments(MotivoFalloLogin.BLOQUEO_TEMPORAL, LoginBloqueadoTemporalmenteException.class));
    }

    @ParameterizedTest(name = "motivo {0}")
    @MethodSource("rechazosYSuMotivo")
    void autenticar_cadaRechazo_registraElIntentoFallidoConSuMotivo( // CP-AUD-01
            MotivoFalloLogin motivo, Class<? extends RuntimeException> excepcionEsperada) {
        // Given: la situación que produce cada motivo
        String contrasena = prepararEscenario(motivo);

        // When
        Throwable lanzada = catchThrowable(
                () -> servicio.autenticar(new SolicitudLoginDTO(EMAIL, contrasena), CONTEXTO));

        // Then: la respuesta es la de siempre y la auditoría recibe el motivo interno, con el correo normalizado
        assertThat(lanzada).isInstanceOf(excepcionEsperada);
        verify(auditoria).registrarLoginFallido(CONTEXTO, EMAIL, motivo);
        verify(auditoria, never()).registrarLoginCorrecto(any(), any(), any());
    }

    /** Configura los colaboradores para el motivo y devuelve la contraseña con la que hay que intentar entrar. */
    private String prepararEscenario(MotivoFalloLogin motivo) {
        switch (motivo) {
            case CORREO_NO_REGISTRADO:
                return CONTRASENA;
            case CONTRASENA_INCORRECTA:
                dadoUsuarioRegistrado(usuario(Rol.CLIENTE, EstadoUsuario.ACTIVO));
                dadaContrasenaCorrecta();
                return CONTRASENA_INCORRECTA;
            case CUENTA_NO_ACTIVA:
                dadoUsuarioRegistrado(usuario(Rol.CLIENTE, EstadoUsuario.BLOQUEADO));
                dadaContrasenaCorrecta();
                return CONTRASENA;
            case ROLES_INVALIDOS:
                Usuario sinRoles = usuario(Rol.CLIENTE, EstadoUsuario.ACTIVO);
                ReflectionTestUtils.setField(sinRoles, "roles", List.of());
                dadoUsuarioRegistrado(sinRoles);
                dadaContrasenaCorrecta();
                return CONTRASENA;
            case BLOQUEO_TEMPORAL:
                doThrow(new LoginBloqueadoTemporalmenteException(30)).when(limitador)
                        .comprobarIntento(EMAIL, CONTEXTO.ip());
                return CONTRASENA;
            default:
                throw new IllegalArgumentException(motivo.name());
        }
    }

    @Test
    void autenticar_datosInvalidos_noSeAudita() { // CP-AUD-01
        // When: unas credenciales mal formadas no son un intento de inicio de sesión
        assertThrows(DatosLoginInvalidosException.class,
                () -> servicio.autenticar(new SolicitudLoginDTO("no-es-un-correo", CONTRASENA), CONTEXTO));

        // Then
        verifyNoInteractions(auditoria);
    }
}
