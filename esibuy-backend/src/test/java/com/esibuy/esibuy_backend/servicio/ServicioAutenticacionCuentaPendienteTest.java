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
import com.esibuy.esibuy_backend.excepcion.CuentaPendienteActivacionException;
import com.esibuy.esibuy_backend.modelo.EstadoUsuario;
import com.esibuy.esibuy_backend.modelo.Rol;

/**
 * Cuenta pendiente de activación: se informa al usuario solo cuando su contraseña es correcta.
 *
 * Reglas que fijan estas pruebas:
 *  - DESACTIVADO con la contraseña correcta: CuentaPendienteActivacionException. No cuenta como intento fallido (la
 *    contraseña era buena) y queda auditado con el motivo CUENTA_NO_ACTIVA.
 *  - DESACTIVADO con la contraseña incorrecta: el rechazo genérico de siempre. Así nadie descubre qué correos existen.
 *  - BLOQUEADO (o sin estado) con la contraseña correcta: también el rechazo genérico.
 *
 * Clase añadida porque ServicioAutenticacionRechazoTest pertenece a una fase cerrada; de ese test se retira el caso
 * "cuenta desactivada con la contraseña correcta", que pasa a comprobarse aquí.
 */
class ServicioAutenticacionCuentaPendienteTest extends ServicioAutenticacionBaseTest {

    private static final String CONTRASENA_INCORRECTA = "Otra-Contrasena-99";

    @Test
    void autenticar_cuentaDesactivadaConContrasenaCorrecta_lanzaCuentaPendienteSinContarFallo() {
        // Given
        dadoUsuarioRegistrado(usuario(Rol.CLIENTE, EstadoUsuario.DESACTIVADO));
        dadaContrasenaCorrecta();

        // When
        CuentaPendienteActivacionException excepcion = assertThrows(CuentaPendienteActivacionException.class,
                () -> servicio.autenticar(new SolicitudLoginDTO(EMAIL, CONTRASENA), CONTEXTO));

        // Then: mensaje fijo sin datos del usuario; la contraseña se verifica una sola vez
        assertThat(excepcion.getMessage()).isEqualTo("Cuenta pendiente de activacion").doesNotContain(EMAIL);
        verify(codificador, times(1)).matches(CONTRASENA, HASH_USUARIO);
        // Then: no es un fallo ni un éxito para el limitador, pero sí queda auditado
        verify(limitador, never()).registrarFallo(any(), any());
        verify(limitador, never()).registrarExito(any());
        verify(auditoria).registrarLoginFallido(CONTEXTO, EMAIL, MotivoFalloLogin.CUENTA_NO_ACTIVA);
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
