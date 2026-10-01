package com.esibuy.esibuy_backend.dto;

/**
 * Datos de registro de un vendedor.
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

    @Override
    public String toString() {
        return "SolicitudRegistroVendedorDTO[nombre=" + nombre
                + ", apellidos=" + apellidos
                + ", categoriaPrincipalId=" + categoriaPrincipalId
                + ", nombreComercial=" + nombreComercial
                + ", dni=" + dni
                + ", email=" + email
                + ", telefono=" + telefono
                + ", avatar=" + avatar
                + ", contrasena=" + DatosSensibles.OCULTO
                + ", repetirContrasena=" + DatosSensibles.OCULTO + "]";
    }
}
