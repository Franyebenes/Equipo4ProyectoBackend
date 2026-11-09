package com.esibuy.esibuy_backend.excepcion;

/** Faltan datos del metodo de pago o alguno no tiene el formato correcto (HTTP 400). */
public class MetodoPagoInvalidoException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public MetodoPagoInvalidoException(String mensaje) {
        super(mensaje);
    }
}