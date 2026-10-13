package com.esibuy.esibuy_backend.dto;

/**
 * Credenciales de inicio de sesion. Mismos nombres de campo que el registro (email, contrasena).
 * El toString() no incluye la contrasena.
 */
public record SolicitudLoginDTO(String email, String contrasena) {

    @Override
    public String toString() {
        return "SolicitudLoginDTO[email=" + email + ", contrasena=" + DatosSensibles.OCULTO + "]";
    }
}
