package com.esibuy.esibuy_backend.dto;

/**
 * Datos de registro de un vendedor ({@code tipoCuenta} = VENDEDOR). El toString() no incluye las contrasenas
 * (CP-REG-12).
 */
public record SolicitudRegistroVendedorDTO(
        TipoCuenta tipoCuenta,
        String nombre,
        String apellidos,
        String categoriaPrincipalId,
        String nombreComercial,
        String dni,
        String email,
        String telefono,
        String avatar,
        String contrasena,
        String repetirContrasena,
        String codigoDosFactores) implements SolicitudRegistro {

    /** Solicitud sin codigo del segundo factor (codigoDosFactores = null). */
    public SolicitudRegistroVendedorDTO(TipoCuenta tipoCuenta, String nombre, String apellidos, String categoriaPrincipalId,
            String nombreComercial, String dni, String email, String telefono, String avatar, String contrasena,
            String repetirContrasena) {
        this(tipoCuenta, nombre, apellidos, categoriaPrincipalId, nombreComercial, dni, email, telefono, avatar,
                contrasena, repetirContrasena, null);
    }

    @Override
    public String toString() {
        return "SolicitudRegistroVendedorDTO[tipoCuenta=" + tipoCuenta
                + ", nombre=" + nombre
                + ", apellidos=" + apellidos
                + ", categoriaPrincipalId=" + categoriaPrincipalId
                + ", nombreComercial=" + nombreComercial
                + ", dni=" + dni
                + ", email=" + email
                + ", telefono=" + telefono
                + ", avatar=" + avatar
                + ", contrasena=" + DatosSensibles.OCULTO
                + ", repetirContrasena=" + DatosSensibles.OCULTO
                + ", codigoDosFactores=" + DatosSensibles.OCULTO + "]";
    }
}
