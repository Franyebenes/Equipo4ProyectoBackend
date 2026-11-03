package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import com.esibuy.esibuy_backend.excepcion.OperacionNoPermitidaException;
import com.esibuy.esibuy_backend.excepcion.UsuarioConProductosException;
import com.esibuy.esibuy_backend.excepcion.UsuarioNoEncontradoException;
import com.esibuy.esibuy_backend.modelo.EstadoUsuario;
import com.esibuy.esibuy_backend.modelo.Rol;

/** Pruebas de eliminar usuarios. */
class ServicioGestionUsuariosEliminarTest extends ServicioGestionUsuariosBase {

    @Test
    void eliminar_usuarioSinProductos_seBorraDeLaBaseDeDatos() {
        // Given
        existeEnBaseDeDatos(usuario(ID_USUARIO, Rol.CLIENTE, EstadoUsuario.ACTIVO));
        when(repositorioProducto.contarPorVendedor(new ObjectId(ID_USUARIO))).thenReturn(0L);

        // When
        servicio.eliminar(ID_USUARIO, ID_ADMIN);

        // Then
        verify(repositorioUsuario).deleteById(ID_USUARIO);
    }

    @Test
    void eliminar_vendedorConProductos_rechazaYNoBorra() {
        // Given
        existeEnBaseDeDatos(usuario(ID_USUARIO, Rol.VENDEDOR, EstadoUsuario.ACTIVO));
        when(repositorioProducto.contarPorVendedor(new ObjectId(ID_USUARIO))).thenReturn(3L);

        // When / Then
        assertThatThrownBy(() -> servicio.eliminar(ID_USUARIO, ID_ADMIN))
                .isInstanceOf(UsuarioConProductosException.class);
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
        verifyNoInteractions(repositorioProducto);
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