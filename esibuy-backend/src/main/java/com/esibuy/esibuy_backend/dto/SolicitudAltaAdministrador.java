package com.esibuy.esibuy_backend.dto;

public record SolicitudAltaAdministradorDTO(
        String nombre,
        String apellidos,
        String email,
        String sede,
        String avatar,
        String contrasena,
        String repetirContrasena,
        String FechaIncorporacion
) {

    public String toString() {
        return "SolicitudAltaAdministradorDTO{" +
                "nombre='" + nombre + '\'' +
                ", apellidos='" + apellidos + '\'' +
                ", email='" + email + '\'' +
                ", sede='" + sede + '\'' +
                ", avatar='" + avatar + '\'' +
                ", contrasena='" + DatosSensibles.OCULTO + '\'' +
                ", repetirContrasena='" + DatosSensibles.OCULTO + '\'' +
                ", FechaIncorporacion='" + FechaIncorporacion + '\'' +
                '}';
    }
}