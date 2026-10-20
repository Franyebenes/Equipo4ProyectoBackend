package com.esibuy.esibuy_backend.excepcion;

/** El producto no existe o no esta visible para los clientes (HTTP 404). */
public class ProductoNoDisponibleException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ProductoNoDisponibleException() {
        super("El producto no existe o no esta disponible");
    }
}