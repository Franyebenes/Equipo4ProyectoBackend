package com.esibuy.esibuy_backend.dto;

import java.time.LocalDate;

/**
 * TODO: sobrescribir toString() para que NO incluya contrasena ni repetirContrasena (CP-REG-12).
 */
public record SolicitudRegistroClienteDTO(
        String nombre,
        String apellidos,
        LocalDate fechaNacimiento,
        String dni,
        String email,
        String telefono,
        String avatar,
        String contrasena,
        String repetirContrasena,
        TipoCliente tipoCliente) {
}
