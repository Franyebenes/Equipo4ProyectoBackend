package com.esibuy.esibuy_backend.servicio;

import java.io.Serializable;

import com.esibuy.esibuy_backend.modelo.Rol;

// Datos publicos de un usuario autenticado: id, email, nombre y un unico rol (CP-LOG-01). Nunca lleva el hash de la contrasena, la contrasena ni el estado interno de la cuenta (CP-LSG-06).
// Es Serializable porque se guarda en la sesion y el contenedor puede persistir las sesiones al reiniciarse.
// avatar: identificador del avatar del perfil (p. ej. "avatar-01") para pintarlo en la cabecera; puede ser null.
public record ResultadoAutenticacion(String id, String email, String nombre, Rol rol, String avatar)
        implements Serializable {

    /** Sin avatar: el usuario no eligio ninguno. */
    public ResultadoAutenticacion(String id, String email, String nombre, Rol rol) {
        this(id, email, nombre, rol, null);
    }
}
