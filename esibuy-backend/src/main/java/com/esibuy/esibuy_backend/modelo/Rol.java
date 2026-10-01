package com.esibuy.esibuy_backend.modelo;

public enum Rol {
    CLIENTE("CLIENTE"),
    PREMIUM("PREMIUM"),
    VENDEDOR("VENDEDOR"),
    ADMINISTRADOR("ADMIN");

    private final String valorBd;

    Rol(String valorBd) {
        this.valorBd = valorBd;
    }

    public String valorBd() {
        return valorBd;
    }
}
