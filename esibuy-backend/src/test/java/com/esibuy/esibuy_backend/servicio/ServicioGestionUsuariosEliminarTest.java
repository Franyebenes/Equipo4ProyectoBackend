package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

import com.esibuy.esibuy_backend.excepcion.OperacionNoPermitidaException;
import com.esibuy.esibuy_backend.excepcion.UsuarioNoEncontradoException;
import com.esibuy.esibuy_backend.modelo.EstadoUsuario;
import com.esibuy.esibuy_backend.modelo.Rol;

/** Pruebas de eliminar usuarios: solo se pueden eliminar otros administradores. */
class ServicioGestionUsuariosEliminarTest extends ServicioGestionUsuariosBase {

    @Test
    void eliminar_otroAdministrador_seBorraDeLaBaseDeDatos() {
        // Given
        existeEnBaseDeDatos(usuario(ID_USUARIO, Rol.ADMIN, EstadoUsuario.ACTIVO));

        // When
        servicio.eliminar(ID_USUARIO, ID_ADMIN);

        // Then
        verify(repositorioUsuario).deleteById(ID_USUARIO);
    }

    @Test
    void eliminar_cliente_rechazaYNoBorra() {
        // Given
        existeEnBaseDeDatos(usuario(ID_USUARIO, Rol.CLIENTE, EstadoUsuario.ACTIVO));

        // When / Then
        assertThatThrownBy(() -> servicio.eliminar(ID_USUARIO, ID_ADMIN))
                .isInstanceOf(OperacionNoPermitidaException.class);
        verify(repositorioUsuario, never()).deleteById(anyString());
    }

    @Test
    void eliminar_vendedor_rechazaYNoBorra() {
        // Given
        existeEnBaseDeDatos(usuario(ID_USUARIO, Rol.VENDEDOR, EstadoUsuario.ACTIVO));

        // When / Then
        assertThatThrownBy(() -> servicio.eliminar(ID_USUARIO, ID_ADMIN))
                .isInstanceOf(OperacionNoPermitidaException.class);
        verify(repositorioUsuario, never()).deleteById(anyString());
    }

    @Test
    void eliminar_unAdminASiMismo_rechazaYNoBorra() {
        // Given
        existeEnBaseDeDatos(usuario(ID_ADMIN, Rol.ADMIN, EstadoUsuario.ACTIVO));

        // When / Then
        assertThatThrownBy(() -> servicio.eliminar(ID_ADMIN, ID_ADMIN))
                .isInstanceOf(OperacionNoPermitidaException.class);
        verify(repositorioUsuario, never()).deleteById(anyString());
    }

    @Test
    void eliminar_usuarioQueNoExiste_devuelveNoEncontrado() {
        // Given
        noExisteEnBaseDeDatos(ID_USUARIO);

        // When / Then
        assertThatThrownBy(() -> servicio.eliminar(ID_USUARIO, ID_ADMIN))
                .isInstanceOf(UsuarioNoEncontradoException.class);
        verify(repositorioUsuario, never()).deleteById(anyString());
    }
}