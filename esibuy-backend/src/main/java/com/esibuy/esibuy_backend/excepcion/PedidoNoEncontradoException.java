package com.esibuy.esibuy_backend.excepcion;

/** El pedido no existe o es de otro cliente (HTTP 404). Los dos casos dan el mismo mensaje. */
public class PedidoNoEncontradoException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PedidoNoEncontradoException() {
        super("El pedido no existe");
    }
}
