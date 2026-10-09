package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;

import com.esibuy.esibuy_backend.dto.SolicitudModificacionUsuarioDTO;
import com.esibuy.esibuy_backend.dto.UsuarioDTO;
import com.esibuy.esibuy_backend.excepcion.UsuarioNoEncontradoException;
import com.esibuy.esibuy_backend.modelo.EstadoUsuario;
import com.esibuy.esibuy_backend.modelo.Rol;
import com.esibuy.esibuy_backend.modelo.Usuario;

/** Pruebas de editar los datos de un usuario. */
class ServicioGestionUsuariosModificarTest extends ServicioGestionUsuariosBase {

    @Test
    void modificar_datosValidos_guardaLosCambiosSinCambiarElEmail() {
        // Given
        Usuario usuario = usuario(ID_USUARIO, Rol.CLIENTE, EstadoUsuario.ACTIVO);
        existeEnBaseDeDatos(usuario);
        guardarDevuelveLoMismo();
        SolicitudModificacionUsuarioDTO solicitud =
                new SolicitudModificacionUsuarioDTO("Ana", "Garcia", "12345678Z", "612345678", "Ciudad Real");

        // When
        UsuarioDTO resultado = servicio.modificar(ID_USUARIO, solicitud);

        // Then
        assertThat(resultado.nombre()).isEqualTo("Ana");
        assertThat(resultado.apellidos()).isEqualTo("Garcia");
        assertThat(resultado.telefono()).isEqualTo("612345678");
        assertThat(resultado.email()).isEqualTo(EMAIL_USUARIO);
        verify(repositorioUsuario).save(usuario);
    }

    @Test
    void modificar_nombreVacio_rechazaSinTocarLaBaseDeDatos() {
        // Given
        SolicitudModificacionUsuarioDTO solicitud =
                new SolicitudModificacionUsuarioDTO("", "Garcia", null, null, null);

        // When / Then
        assertThatThrownBy(() -> servicio.modificar(ID_USUARIO, solicitud))
                .isInstanceOf(RuntimeException.class);
        verifyNoInteractions(repositorioUsuario);
    }

    @Test
    void modificar_telefonoInvalido_rechazaSinTocarLaBaseDeDatos() {
        // Given
        SolicitudModificacionUsuarioDTO solicitud =
                new SolicitudModificacionUsuarioDTO("Ana", "Garcia", null, "123", null);

        // When / Then
        assertThatThrownBy(() -> servicio.modificar(ID_USUARIO, solicitud))
                .isInstanceOf(RuntimeException.class);
        verifyNoInteractions(repositorioUsuario);
    }

    @Test
    void modificar_usuarioQueNoExiste_devuelveNoEncontrado() {
        // Given
        noExisteEnBaseDeDatos(ID_USUARIO);
        SolicitudModificacionUsuarioDTO solicitud =
                new SolicitudModificacionUsuarioDTO("Ana", "Garcia", null, null, null);

        // When / Then
        assertThatThrownBy(() -> servicio.modificar(ID_USUARIO, solicitud))
                .isInstanceOf(UsuarioNoEncontradoException.class);
        verify(repositorioUsuario, never()).save(any());
    }
}