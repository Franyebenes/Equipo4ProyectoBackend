package com.esibuy.esibuy_backend.dto;

/*
 * Restablecimiento de contrasena: el token del enlace recibido por correo y la contrasena nueva. El toString() no
 * incluye ni el token ni la contrasena.
 */
public record SolicitudRestablecimientoDTO(String token, String contrasena) {

    @Override
    public String toString() {
        return "SolicitudRestablecimientoDTO[token=" + DatosSensibles.OCULTO + ", contrasena="
                + DatosSensibles.OCULTO + "]";
    }
}
