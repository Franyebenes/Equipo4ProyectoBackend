package com.esibuy.esibuy_backend.excepcion;

/** El cliente ya valoro ese producto en ese pedido; la valoracion no se puede repetir ni modificar (HTTP 409). */
public class ProductoYaValoradoException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ProductoYaValoradoException() {
        super("Ya has valorado este producto");
    }
}
