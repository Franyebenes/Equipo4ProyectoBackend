package com.esibuy.esibuy_backend.excepcion;

/** El producto que se quiere modificar o quitar no esta en el carrito del cliente (HTTP 404). */
public class ProductoNoEnCarritoException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ProductoNoEnCarritoException() {
        super("El producto no esta en tu carrito");
    }
}