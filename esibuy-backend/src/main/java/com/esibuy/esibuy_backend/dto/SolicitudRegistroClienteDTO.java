package com.esibuy.esibuy_backend.dto;

import java.time.LocalDate;

/**
 * Datos de registro de un cliente ({@code tipoCuenta} = CLIENTE o PREMIUM). El toString() no incluye las
 * contrasenas (CP-REG-12).
 */
public record SolicitudRegistroClienteDTO(
        TipoCuenta tipoCuenta,
        String nombre,
        String apellidos,
        LocalDate fechaNacimiento,
        String dni,
        String email,
        String telefono,
        String avatar,
        String contrasena,
        String repetirContrasena) implements SolicitudRegistro {

    @Override
    public String toString() {
        return "SolicitudRegistroClienteDTO[tipoCuenta=" + tipoCuenta
                + ", nombre=" + nombre
                + ", apellidos=" + apellidos
                + ", fechaNacimiento=" + fechaNacimiento
                + ", dni=" + dni
                + ", email=" + email
                + ", telefono=" + telefono
                + ", avatar=" + avatar
                + ", contrasena=" + DatosSensibles.OCULTO
                + ", repetirContrasena=" + DatosSensibles.OCULTO + "]";
    }
}
