package com.esibuy.esibuy_backend.excepcion;

/** Faltan campos obligatorios del producto o alguno no es valido (HTTP 400). */
public class ProductoInvalidoException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ProductoInvalidoException(String mensaje) {
        super(mensaje);
    }
}
