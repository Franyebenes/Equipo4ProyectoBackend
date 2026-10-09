package com.esibuy.esibuy_backend.dto;

import com.esibuy.esibuy_backend.modelo.Rol;

/**
 * Respuesta de un inicio de sesion correcto: datos publicos del usuario y su unico rol. Nunca lleva el hash, la
 * contrasena ni el identificador de sesion (que viaja solo en la cookie).
 */
public record RespuestaLoginDTO(String id, String email, String nombre, Rol rol, String mensaje) {
}
