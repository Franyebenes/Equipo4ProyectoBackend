package com.esibuy.esibuy_backend.excepcion;

/** El estado actual del pedido no permite la operacion: cancelarlo, confirmar su recepcion o valorarlo (HTTP 409). */
public class EstadoPedidoNoPermitidoException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public EstadoPedidoNoPermitidoException(String mensaje) {
        super(mensaje);
    }
}
