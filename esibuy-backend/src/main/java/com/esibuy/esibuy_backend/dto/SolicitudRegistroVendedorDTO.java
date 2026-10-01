package com.esibuy.esibuy_backend.dto;

/**
 * TODO: sobrescribir toString() para que NO incluya contrasena ni repetirContrasena (CP-REG-12).
 */
public record SolicitudRegistroVendedorDTO(
        String nombre,
        String apellidos,
        String categoriaPrincipalId,
        String nombreComercial,
        String dni,
        String email,
        String telefono,
        String avatar,
        String contrasena,
        String repetirContrasena) {
}
