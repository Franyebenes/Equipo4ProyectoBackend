package com.esibuy.esibuy_backend.dto;

import java.time.LocalDate;

import com.esibuy.esibuy_backend.modelo.PerfilUsuario;
import com.esibuy.esibuy_backend.modelo.Usuario;

/** Datos que ve el propio usuario en su perfil. */
public record PerfilDTO(
        String id,
        String email,
        String rol,
        String nombre,
        String apellidos,
        String dni,
        LocalDate fechaNacimiento,
        String telefono,
        String avatar,
        String nombreComercial,
        String sede) {

    public static PerfilDTO desde(Usuario usuario) {
        PerfilUsuario perfil = usuario.getPerfil();
        return new PerfilDTO(
                usuario.getId(),
                usuario.getEmail(),
                usuario.getRoles().get(0).name(),
                perfil.getNombre(),
                perfil.getApellidos(),
                perfil.getDni(),
                perfil.getFechaNacimiento(),
                perfil.getTelefono(),
                perfil.getAvatarUrl(),
                perfil.getNombreComercial(),
                perfil.getSede());
    }
}