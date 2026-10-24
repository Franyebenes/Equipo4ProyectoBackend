package com.esibuy.esibuy_backend.excepcion;

/** El producto que se quiere valorar no forma parte de ese pedido (HTTP 404). */
public class ProductoNoEnPedidoException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ProductoNoEnPedidoException() {
        super("El producto no está en este pedido");
    }
}
