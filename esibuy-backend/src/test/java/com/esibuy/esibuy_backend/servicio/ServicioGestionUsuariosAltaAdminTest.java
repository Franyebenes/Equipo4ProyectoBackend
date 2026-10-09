package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.esibuy.esibuy_backend.dto.RespuestaRegistroDTO;
import com.esibuy.esibuy_backend.excepcion.RegistroNoCompletadoException;
import com.esibuy.esibuy_backend.excepcion.ServicioNoDisponibleException;
import com.esibuy.esibuy_backend.modelo.Rol;
import com.esibuy.esibuy_backend.modelo.Usuario;

/** Pruebas de dar de alta a un administrador. */
class ServicioGestionUsuariosAltaAdminTest extends ServicioGestionUsuariosBase {

    @Test
    void crearAdministrador_datosValidos_guardaUnUsuarioConRolAdminYFechaDeHoy() {
        // Given
        when(validadorDominioEmail.tieneDominioValido(EMAIL_NUEVO_ADMIN)).thenReturn(true);
        when(repositorioUsuario.existePorEmail(EMAIL_NUEVO_ADMIN)).thenReturn(false);
        when(codificadorContrasena.encode(CONTRASENA)).thenReturn("hash-seguro");
        when(repositorioUsuario.save(any(Usuario.class))).thenAnswer(invocacion -> {
            Usuario nuevo = invocacion.getArgument(0);
            ReflectionTestUtils.setField(nuevo, "id", ID_USUARIO);
            return nuevo;
        });

        // When
        RespuestaRegistroDTO respuesta = servicio.crearAdministrador(solicitudAlta(CONTRASENA, CONTRASENA));

        // Then
        ArgumentCaptor<Usuario> guardado = ArgumentCaptor.forClass(Usuario.class);
        verify(repositorioUsuario).save(guardado.capture());
        assertThat(guardado.getValue().getRoles()).containsExactly(Rol.ADMIN);
        assertThat(guardado.getValue().getPerfil().getFechaIncorporacion()).isEqualTo(LocalDate.of(2026, 10, 9));
        assertThat(respuesta.email()).isEqualTo(EMAIL_NUEVO_ADMIN);
    }

    @Test
    void crearAdministrador_emailYaRegistrado_rechazaYNoGuarda() {
        // Given
        when(validadorDominioEmail.tieneDominioValido(EMAIL_NUEVO_ADMIN)).thenReturn(true);
        when(repositorioUsuario.existePorEmail(EMAIL_NUEVO_ADMIN)).thenReturn(true);

        // When / Then
        assertThatThrownBy(() -> servicio.crearAdministrador(solicitudAlta(CONTRASENA, CONTRASENA)))
                .isInstanceOf(RegistroNoCompletadoException.class);
        verify(repositorioUsuario, never()).save(any());
    }

    @Test
    void crearAdministrador_contrasenasDistintas_rechazaAntesDeComprobarElEmail() {
        // When / Then
        assertThatThrownBy(() -> servicio.crearAdministrador(solicitudAlta(CONTRASENA, "Otra.Contrasena2026")))
                .isInstanceOf(RuntimeException.class);
        verifyNoInteractions(validadorDominioEmail);
        verify(repositorioUsuario, never()).save(any());
    }

    @Test
    void crearAdministrador_dominioDeEmailInexistente_rechazaYNoGuarda() {
        // Given
        when(validadorDominioEmail.tieneDominioValido(EMAIL_NUEVO_ADMIN)).thenReturn(false);

        // When / Then
        assertThatThrownBy(() -> servicio.crearAdministrador(solicitudAlta(CONTRASENA, CONTRASENA)))
                .isInstanceOf(RuntimeException.class);
        verify(repositorioUsuario, never()).save(any());
    }

    @Test
    void crearAdministrador_servicioDeDominiosCaido_devuelveServicioNoDisponible() {
        // Given
        when(validadorDominioEmail.tieneDominioValido(anyString())).thenThrow(new RuntimeException("DNS caido"));

        // When / Then
        assertThatThrownBy(() -> servicio.crearAdministrador(solicitudAlta(CONTRASENA, CONTRASENA)))
                .isInstanceOf(ServicioNoDisponibleException.class);
        verify(repositorioUsuario, never()).save(any());
    }
}