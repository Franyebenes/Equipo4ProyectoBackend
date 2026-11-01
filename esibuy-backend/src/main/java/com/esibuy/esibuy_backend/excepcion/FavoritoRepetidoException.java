package com.esibuy.esibuy_backend.excepcion;

/** El producto ya esta en los favoritos del cliente: no se anade otra vez (HTTP 409). */
public class FavoritoRepetidoException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public FavoritoRepetidoException() {
        super("Este producto ya está en tus favoritos");
    }
}
