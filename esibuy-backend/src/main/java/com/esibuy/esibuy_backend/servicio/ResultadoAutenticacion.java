package com.esibuy.esibuy_backend.servicio;

import java.io.Serializable;

import com.esibuy.esibuy_backend.modelo.Rol;

// Datos publicos de un usuario autenticado: id, email, nombre y un unico rol (CP-LOG-01). Nunca lleva el hash de la contrasena, la contrasena ni el estado interno de la cuenta (CP-LSG-06).
// Es Serializable porque se guarda en la sesion y el contenedor puede persistir las sesiones al reiniciarse.
public record ResultadoAutenticacion(String id, String email, String nombre, Rol rol) implements Serializable {
}
