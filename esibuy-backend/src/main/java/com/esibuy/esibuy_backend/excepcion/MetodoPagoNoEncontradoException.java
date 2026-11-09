package com.esibuy.esibuy_backend.excepcion;

/** El metodo de pago no existe o es de otro cliente (HTTP 404). Mismo mensaje en los dos casos. */
public class MetodoPagoNoEncontradoException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public MetodoPagoNoEncontradoException() {
        super("El metodo de pago no existe");
    }
}