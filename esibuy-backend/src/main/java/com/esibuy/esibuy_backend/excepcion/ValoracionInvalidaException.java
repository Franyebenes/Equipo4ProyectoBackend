package com.esibuy.esibuy_backend.excepcion;

/** La puntuacion no es valida: tiene que ser un numero entero entre 1 y 5 (HTTP 400). */
public class ValoracionInvalidaException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ValoracionInvalidaException() {
        super("La valoración debe estar entre 1 y 5 estrellas");
    }
}
