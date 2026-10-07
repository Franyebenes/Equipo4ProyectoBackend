package com.esibuy.esibuy_backend.dto;

// La fecha de incorporacion no se recibe: el servicio asigna la fecha del alta.
public record SolicitudAltaAdministradorDTO(
        String nombre,
        String apellidos,
        String email,
        String sede,
        String avatar,
        String contrasena,
        String repetirContrasena
) {

    @Override
    public String toString() {
        return "SolicitudAltaAdministradorDTO{" +
                "nombre='" + nombre + '\'' +
                ", apellidos='" + apellidos + '\'' +
                ", email='" + email + '\'' +
                ", sede='" + sede + '\'' +
                ", avatar='" + avatar + '\'' +
                ", contrasena='" + DatosSensibles.OCULTO + '\'' +
                ", repetirContrasena='" + DatosSensibles.OCULTO + '\'' +
                '}';
    }
}
