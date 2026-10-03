package com.esibuy.esibuy_backend.util;

import com.esibuy.esibuy_backend.dto.SolicitudRegistroClienteDTO;
import com.esibuy.esibuy_backend.dto.TipoCliente;

import java.time.LocalDate;

/**
 * Construye solicitudes de registro de cliente. Por defecto produce una solicitud
 * completamente valida; cada test modifica solo el campo que le interesa.
 */
public final class ConstructorSolicitudCliente {

    public static final String EMAIL_POR_DEFECTO = "ana.garcia@ejemplo.es";
    public static final String CONTRASENA_VALIDA = "Tren-Azul-Lluvia-77";

    private String nombre = "Ana";
    private String apellidos = "Garc\u00eda L\u00f3pez";
    private LocalDate fechaNacimiento = LocalDate.of(2000, 5, 15);
    private String dni = "12345678Z";
    private String email = EMAIL_POR_DEFECTO;
    private String telefono = "612345678";
    private String avatar = "avatar-01";
    private String contrasena = CONTRASENA_VALIDA;
    private String repetirContrasena = CONTRASENA_VALIDA;
    private TipoCliente tipoCliente = TipoCliente.NORMAL;

    private ConstructorSolicitudCliente() {
    }

    public static ConstructorSolicitudCliente unaSolicitudValida() {
        return new ConstructorSolicitudCliente();
    }

    public ConstructorSolicitudCliente conNombre(String valor) {
        this.nombre = valor;
        return this;
    }

    public ConstructorSolicitudCliente conApellidos(String valor) {
        this.apellidos = valor;
        return this;
    }

    public ConstructorSolicitudCliente conFechaNacimiento(LocalDate valor) {
        this.fechaNacimiento = valor;
        return this;
    }

    public ConstructorSolicitudCliente conDni(String valor) {
        this.dni = valor;
        return this;
    }

    public ConstructorSolicitudCliente conEmail(String valor) {
        this.email = valor;
        return this;
    }

    public ConstructorSolicitudCliente conTelefono(String valor) {
        this.telefono = valor;
        return this;
    }

    public ConstructorSolicitudCliente conAvatar(String valor) {
        this.avatar = valor;
        return this;
    }

    /** Fija la contrasena y su repeticion con el mismo valor. */
    public ConstructorSolicitudCliente conContrasena(String valor) {
        this.contrasena = valor;
        this.repetirContrasena = valor;
        return this;
    }

    public ConstructorSolicitudCliente conRepeticionContrasena(String valor) {
        this.repetirContrasena = valor;
        return this;
    }

    public ConstructorSolicitudCliente conTipoCliente(TipoCliente valor) {
        this.tipoCliente = valor;
        return this;
    }

    public SolicitudRegistroClienteDTO construir() {
        return new SolicitudRegistroClienteDTO(nombre, apellidos, fechaNacimiento, dni, email,
                telefono, avatar, contrasena, repetirContrasena, tipoCliente);
    }
}
