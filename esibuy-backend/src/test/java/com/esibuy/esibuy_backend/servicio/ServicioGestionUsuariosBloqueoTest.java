package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;

import com.esibuy.esibuy_backend.dto.UsuarioDTO;
import com.esibuy.esibuy_backend.excepcion.OperacionNoPermitidaException;
import com.esibuy.esibuy_backend.excepcion.UsuarioNoEncontradoException;
import com.esibuy.esibuy_backend.modelo.EstadoUsuario;
import com.esibuy.esibuy_backend.modelo.Rol;
import com.esibuy.esibuy_backend.modelo.Usuario;

/** Pruebas de bloquear y desbloquear usuarios. */
class ServicioGestionUsuariosBloqueoTest extends ServicioGestionUsuariosBase {

    // ------------------------------------------------------------------ bloquear

    @Test
    void bloquear_usuarioActivo_pasaABloqueadoYSeGuarda() {
        // Given
        Usuario usuario = usuario(ID_USUARIO, Rol.CLIENTE, EstadoUsuario.ACTIVO);
        existeEnBaseDeDatos(usuario);
        guardarDevuelveLoMismo();

        // When
        UsuarioDTO resultado = servicio.bloquear(ID_USUARIO, ID_ADMIN);

        // Then
        assertThat(resultado.estado()).isEqualTo(EstadoUsuario.BLOQUEADO);
        verify(repositorioUsuario).save(usuario);
    }

    @Test
    void bloquear_usuarioPendienteDeActivacion_pasaABloqueado() {
        // Given
        Usuario usuario = usuario(ID_USUARIO, Rol.CLIENTE, EstadoUsuario.DESACTIVADO);
        existeEnBaseDeDatos(usuario);
        guardarDevuelveLoMismo();

        // When
        UsuarioDTO resultado = servicio.bloquear(ID_USUARIO, ID_ADMIN);

        // Then
        assertThat(resultado.estado()).isEqualTo(EstadoUsuario.BLOQUEADO);
    }

    @Test
    void bloquear_usuarioYaBloqueado_rechazaYNoGuarda() {
        // Given
        existeEnBaseDeDatos(usuario(ID_USUARIO, Rol.CLIENTE, EstadoUsuario.BLOQUEADO));

        // When / Then
        assertThatThrownBy(() -> servicio.bloquear(ID_USUARIO, ID_ADMIN))
                .isInstanceOf(OperacionNoPermitidaException.class);
        verify(repositorioUsuario, never()).save(any());
    }

    @Test
    void bloquear_unAdminASiMismo_rechazaYNoGuarda() {
        // Given
        existeEnBaseDeDatos(usuario(ID_ADMIN, Rol.ADMIN, EstadoUsuario.ACTIVO));

        // When / Then
        assertThatThrownBy(() -> servicio.bloquear(ID_ADMIN, ID_ADMIN))
                .isInstanceOf(OperacionNoPermitidaException.class);
        verify(repositorioUsuario, never()).save(any());
    }

    @Test
    void bloquear_idConFormatoInvalido_devuelveNoEncontradoSinConsultarLaBaseDeDatos() {
        // When / Then
        assertThatThrownBy(() -> servicio.bloquear("no-es-un-id", ID_ADMIN))
                .isInstanceOf(UsuarioNoEncontradoException.class);
        verifyNoInteractions(repositorioUsuario);
    }

    @Test
    void bloquear_usuarioQueNoExiste_devuelveNoEncontrado() {
        // Given
        noExisteEnBaseDeDatos(ID_USUARIO);

        // When / Then
        assertThatThrownBy(() -> servicio.bloquear(ID_USUARIO, ID_ADMIN))
                .isInstanceOf(UsuarioNoEncontradoException.class);
        verify(repositorioUsuario, never()).save(any());
    }

    // ------------------------------------------------------------------ desbloquear

    @Test
    void desbloquear_usuarioBloqueado_pasaAActivo() {
        // Given
        Usuario usuario = usuario(ID_USUARIO, Rol.CLIENTE, EstadoUsuario.BLOQUEADO);
        existeEnBaseDeDatos(usuario);
        guardarDevuelveLoMismo();

        // When
        UsuarioDTO resultado = servicio.desbloquear(ID_USUARIO, ID_ADMIN);

        // Then
        assertThat(resultado.estado()).isEqualTo(EstadoUsuario.ACTIVO);
        verify(repositorioUsuario).save(usuario);
    }

    @Test
    void desbloquear_usuarioPendienteDeActivacion_quedaActivado() {
        // Given
        existeEnBaseDeDatos(usuario(ID_USUARIO, Rol.VENDEDOR, EstadoUsuario.DESACTIVADO));
        guardarDevuelveLoMismo();

        // When
        UsuarioDTO resultado = servicio.desbloquear(ID_USUARIO, ID_ADMIN);

        // Then
        assertThat(resultado.estado()).isEqualTo(EstadoUsuario.ACTIVO);
    }

    @Test
    void desbloquear_usuarioActivo_rechazaYNoGuarda() {
        // Given
        existeEnBaseDeDatos(usuario(ID_USUARIO, Rol.CLIENTE, EstadoUsuario.ACTIVO));

        // When / Then
        assertThatThrownBy(() -> servicio.desbloquear(ID_USUARIO, ID_ADMIN))
                .isInstanceOf(OperacionNoPermitidaException.class);
        verify(repositorioUsuario, never()).save(any());
    }
}