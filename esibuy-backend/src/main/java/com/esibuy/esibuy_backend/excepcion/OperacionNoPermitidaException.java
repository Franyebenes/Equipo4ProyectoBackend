package com.esibuy.esibuy_backend.excepcion;

public class OperacionNoPermitidaException extends RuntimeException {

    /** Builds the exception with a fixed message; the reason is deliberately not detailed to the client. */
    public OperacionNoPermitidaException() {
        super("Operacion no permitida");
    }
}
