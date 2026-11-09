package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

import com.esibuy.esibuy_backend.dto.SolicitudLoginDTO;
import com.esibuy.esibuy_backend.excepcion.CredencialesInvalidasException;
import com.esibuy.esibuy_backend.modelo.EstadoUsuario;
import com.esibuy.esibuy_backend.modelo.Rol;

/**
 * Cuenta pendiente de activación: el usuario recibe siempre el rechazo genérico, sin saber que su cuenta está
 * desactivada.
 *
 * Reglas que fijan estas pruebas:
 *  - DESACTIVADO con la contraseña correcta: el rechazo genérico (CredencialesInvalidasException). No cuenta como
 *    intento fallido del limitador (la contraseña era buena) y se audita con su evento propio
 *    (LOGIN_CUENTA_NO_ACTIVA), no como LOGIN_FALLIDO.
 *  - DESACTIVADO con la contraseña incorrecta: el rechazo genérico de siempre, que sí cuenta como fallo.
 *  - BLOQUEADO (o sin estado) con la contraseña correcta: también el rechazo genérico, contando como fallo.
 *
 * Clase añadida porque ServicioAutenticacionRechazoTest pertenece a una fase cerrada; de ese test se retira el caso
 * "cuenta desactivada con la contraseña correcta", que pasa a comprobarse aquí.
 */
class ServicioAutenticacionCuentaPendienteTest extends ServicioAutenticacionBaseTest {

    private static final String CONTRASENA_INCORRECTA = "Otra-Contrasena-99";

    @Test
    void autenticar_cuentaDesactivadaConContrasenaCorrecta_lanzaElRechazoGenericoSinContarFallo() {
        // Given
        dadoUsuarioRegistrado(usuario(Rol.CLIENTE, EstadoUsuario.DESACTIVADO));
        dadaContrasenaCorrecta();

        // When
        CredencialesInvalidasException excepcion = assertThrows(CredencialesInvalidasException.class,
                () -> servicio.autenticar(new SolicitudLoginDTO(EMAIL, CONTRASENA), CONTEXTO));

        // Then: mensaje genérico sin datos del usuario ni mención a la activación; la contraseña se verifica una vez
        assertThat(excepcion.getMessage()).isEqualTo("Credenciales invalidas")
                .doesNotContain(EMAIL).doesNotContainIgnoringCase("activ").doesNotContainIgnoringCase("desactiv");
        verify(codificador, times(1)).matches(CONTRASENA, HASH_USUARIO);
        // Then: no es un fallo ni un éxito para el limitador, y se audita con su evento propio, no como login fallido
        verify(limitador, never()).registrarFallo(any(), any());
        verify(limitador, never()).registrarExito(any());
        verify(auditoria).registrarLoginCuentaNoActiva(CONTEXTO, EMAIL);
        verify(auditoria, never()).registrarLoginFallido(any(), any(), any());
        verify(auditoria, never()).registrarLoginCorrecto(any(), any(), any());
    }

    @Test
    void autenticar_cuentaDesactivadaConContrasenaIncorrecta_lanzaElRechazoGenerico() {
        // Given
        dadoUsuarioRegistrado(usuario(Rol.CLIENTE, EstadoUsuario.DESACTIVADO));
        dadaContrasenaCorrecta();

        // When / Then: sin la contraseña correcta no se revela nada de la cuenta
        assertThrows(CredencialesInvalidasException.class,
                () -> servicio.autenticar(new SolicitudLoginDTO(EMAIL, CONTRASENA_INCORRECTA), CONTEXTO));
        verify(limitador).registrarFallo(EMAIL, CONTEXTO.ip());
        verify(auditoria).registrarLoginFallido(CONTEXTO, EMAIL, MotivoFalloLogin.CONTRASENA_INCORRECTA);
    }

    @Test
    void autenticar_cuentaBloqueadaConContrasenaCorrecta_sigueSiendoElRechazoGenerico() {
        // Given
        dadoUsuarioRegistrado(usuario(Rol.CLIENTE, EstadoUsuario.BLOQUEADO));
        dadaContrasenaCorrecta();

        // When / Then
        assertThrows(CredencialesInvalidasException.class,
                () -> servicio.autenticar(new SolicitudLoginDTO(EMAIL, CONTRASENA), CONTEXTO));
        verify(limitador).registrarFallo(EMAIL, CONTEXTO.ip());
    }

    @Test
    void autenticar_cuentaSinEstadoConContrasenaCorrecta_sigueSiendoElRechazoGenerico() {
        // Given: un documento sin estado no se interpreta como pendiente de activación
        dadoUsuarioRegistrado(usuario(Rol.CLIENTE, null));
        dadaContrasenaCorrecta();

        // When / Then
        assertThrows(CredencialesInvalidasException.class,
                () -> servicio.autenticar(new SolicitudLoginDTO(EMAIL, CONTRASENA), CONTEXTO));
    }
}
