package com.esibuy.esibuy_backend.servicio;

import com.esibuy.esibuy_backend.modelo.Rol;

// Datos publicos de un usuario autenticado: id, email, nombre y un unico rol (CP-LOG-01). Nunca lleva el hash de la contrasena, la contrasena ni el estado interno de la cuenta (CP-LSG-06).
public record ResultadoAutenticacion(String id, String email, String nombre, Rol rol) {
}
