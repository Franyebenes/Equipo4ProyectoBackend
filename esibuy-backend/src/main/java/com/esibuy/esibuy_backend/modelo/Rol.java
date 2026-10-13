package com.esibuy.esibuy_backend.modelo;

/** Rol de un usuario. Se guarda en MongoDB con el mismo nombre, sin conversor. */
public enum Rol {
    CLIENTE, PREMIUM, VENDEDOR, ADMIN;

    /** Valor que se guarda en MongoDB (CP-INT-08). */
    public String valorBd() {
        return name();
    }
}
