package com.esibuy.esibuy_backend.dto;

import java.time.LocalDate;

/**
 * Datos de registro de un cliente.
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

    @Override
    public String toString() {
        return "SolicitudRegistroClienteDTO[nombre=" + nombre
                + ", apellidos=" + apellidos
                + ", fechaNacimiento=" + fechaNacimiento
                + ", dni=" + dni
                + ", email=" + email
                + ", telefono=" + telefono
                + ", avatar=" + avatar
                + ", contrasena=" + DatosSensibles.OCULTO
                + ", repetirContrasena=" + DatosSensibles.OCULTO
                + ", tipoCliente=" + tipoCliente + "]";
    }
}
