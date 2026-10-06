package com.esibuy.esibuy_backend.util;

import com.esibuy.esibuy_backend.dto.SolicitudRegistroVendedorDTO;
import com.esibuy.esibuy_backend.dto.TipoCuenta;

/**
 * Construye solicitudes de registro de vendedor. Por defecto produce una solicitud
 * completamente valida; cada test modifica solo el campo que le interesa.
 */
public final class ConstructorSolicitudVendedor {

    public static final String EMAIL_POR_DEFECTO = "luis.perez@tienda.es";
    public static final String CONTRASENA_VALIDA = "Tren-Azul-Lluvia-77";
    public static final String NOMBRE_COMERCIAL_POR_DEFECTO = "Tienda Norte";
    /** ObjectId valido (24 caracteres hexadecimales). */
    public static final String CATEGORIA_VALIDA = "64b7f0c2a1b2c3d4e5f60718";

    private String nombre = "Luis";
    private String apellidos = "P\u00e9rez Mora";
    private String categoriaPrincipalId = CATEGORIA_VALIDA;
    private String nombreComercial = NOMBRE_COMERCIAL_POR_DEFECTO;
    private String dni = "B12345678";
    private String email = EMAIL_POR_DEFECTO;
    private String telefono = "699887766";
    private String avatar = "avatar-01";
    private String contrasena = CONTRASENA_VALIDA;
    private String repetirContrasena = CONTRASENA_VALIDA;

    private ConstructorSolicitudVendedor() {
    }

    public static ConstructorSolicitudVendedor unaSolicitudValida() {
        return new ConstructorSolicitudVendedor();
    }

    public ConstructorSolicitudVendedor conNombre(String valor) {
        this.nombre = valor;
        return this;
    }

    public ConstructorSolicitudVendedor conApellidos(String valor) {
        this.apellidos = valor;
        return this;
    }

    public ConstructorSolicitudVendedor conCategoriaPrincipalId(String valor) {
        this.categoriaPrincipalId = valor;
        return this;
    }

    public ConstructorSolicitudVendedor conNombreComercial(String valor) {
        this.nombreComercial = valor;
        return this;
    }

    public ConstructorSolicitudVendedor conDni(String valor) {
        this.dni = valor;
        return this;
    }

    public ConstructorSolicitudVendedor conEmail(String valor) {
        this.email = valor;
        return this;
    }

    public ConstructorSolicitudVendedor conTelefono(String valor) {
        this.telefono = valor;
        return this;
    }

    public ConstructorSolicitudVendedor conAvatar(String valor) {
        this.avatar = valor;
        return this;
    }

    /** Fija la contrasena y su repeticion con el mismo valor. */
    public ConstructorSolicitudVendedor conContrasena(String valor) {
        this.contrasena = valor;
        this.repetirContrasena = valor;
        return this;
    }

    public SolicitudRegistroVendedorDTO construir() {
        return new SolicitudRegistroVendedorDTO(TipoCuenta.VENDEDOR, nombre, apellidos, categoriaPrincipalId,
                nombreComercial, dni, email, telefono, avatar, contrasena, repetirContrasena);
    }
}
