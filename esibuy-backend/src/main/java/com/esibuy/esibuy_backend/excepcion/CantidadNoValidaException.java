package com.esibuy.esibuy_backend.excepcion;

/** La cantidad pedida para el carrito no es valida: tiene que ser al menos 1 (HTTP 400). */
public class CantidadNoValidaException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CantidadNoValidaException() {
        super("La cantidad debe ser al menos 1");
    }
}