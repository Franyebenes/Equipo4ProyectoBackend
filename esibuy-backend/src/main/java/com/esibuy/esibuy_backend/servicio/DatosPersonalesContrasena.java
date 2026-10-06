package com.esibuy.esibuy_backend.servicio;

/** Datos del usuario que no pueden aparecer dentro de su contrasena. nombreComercial puede ser null. */
public record DatosPersonalesContrasena(String nombre, String apellidos, String email, String nombreComercial) {
}
