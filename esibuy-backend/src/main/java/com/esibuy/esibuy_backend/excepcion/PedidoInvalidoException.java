package com.esibuy.esibuy_backend.excepcion;

/** No se puede realizar el pedido con lo que ha enviado el cliente: carrito vacio, direccion incompleta... (HTTP 400). */
public class PedidoInvalidoException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PedidoInvalidoException(String mensaje) {
        super(mensaje);
    }
}
