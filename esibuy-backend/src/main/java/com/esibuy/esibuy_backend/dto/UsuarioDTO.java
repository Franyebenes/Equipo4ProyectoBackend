package com.esibuy.esibuy_backend.dto;

import java.time.LocalDate;
import java.util.List;

import com.esibuy.esibuy_backend.modelo.EstadoUsuario;
import com.esibuy.esibuy_backend.modelo.PerfilUsuario;
import com.esibuy.esibuy_backend.modelo.Rol;
import com.esibuy.esibuy_backend.modelo.Usuario;


public record UsuarioDTO(String id, String email, String nombre, String apellidos, String dni, String telefono,
                         String nombreComercial, String categoriaPrincipalId, LocalDate fechaNacimiento,
                         String sede, String avatar, LocalDate fechaIncorporacion,
                         List<Rol> roles, EstadoUsuario estado) {

    public static UsuarioDTO desde(Usuario usuario) {
        PerfilUsuario perfil = usuario.getPerfil();
        return new UsuarioDTO(usuario.getId(), usuario.getEmail(), perfil.getNombre(), perfil.getApellidos(),
                perfil.getDni(), perfil.getTelefono(), perfil.getNombreComercial(), perfil.getCategoriaPrincipalId(),
                perfil.getFechaNacimiento(), perfil.getSede(), perfil.getAvatarUrl(), perfil.getFechaIncorporacion(),
                usuario.getRoles(), usuario.getEstado());
    }
}

