package com.esibuy.esibuy_backend.modelo;

public enum Rol {
    CLIENTE, PREMIUM, VENDEDOR, ADMINISTRADOR;

    /**
     * Valor que se guarda en MongoDB (en ingles): CUSTOMER, PREMIUM, SELLER, ADMIN.
     * Tests: CP-INT-08.
     */
    public String valorBd() {
        throw new UnsupportedOperationException("Pendiente de implementar");
    }
}
