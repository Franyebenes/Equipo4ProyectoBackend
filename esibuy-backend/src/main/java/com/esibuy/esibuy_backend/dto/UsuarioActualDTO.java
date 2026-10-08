package com.esibuy.esibuy_backend.dto;

import com.esibuy.esibuy_backend.modelo.Rol;

/**
 * Datos publicos del usuario de la sesion actual (respuesta de GET /api/auth/me): id, email, nombre y su unico rol.
 * Nunca lleva el hash, la contrasena ni el identificador de sesion.
 */
public record UsuarioActualDTO(String id, String email, String nombre, Rol rol) {
}
