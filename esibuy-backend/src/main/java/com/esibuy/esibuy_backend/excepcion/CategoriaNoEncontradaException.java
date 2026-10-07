package com.esibuy.esibuy_backend.excepcion;

/** La categoría que se quiere eliminar no existe (HTTP 404). */
public class CategoriaNoEncontradaException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CategoriaNoEncontradaException() {
        super("La categoría no existe o ya ha sido eliminada");
    }
}