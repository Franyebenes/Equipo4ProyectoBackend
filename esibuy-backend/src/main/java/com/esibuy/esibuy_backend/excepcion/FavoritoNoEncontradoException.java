package com.esibuy.esibuy_backend.excepcion;

/** El producto no esta en los favoritos del cliente: no hay nada que quitar (HTTP 404). */
public class FavoritoNoEncontradoException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public FavoritoNoEncontradoException() {
        super("Este producto no está en tus favoritos");
    }
}
