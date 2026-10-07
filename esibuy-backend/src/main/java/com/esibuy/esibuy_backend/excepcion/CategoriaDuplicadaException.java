package com.esibuy.esibuy_backend.excepcion;

/** Ya existe otra categoría con ese nombre, sin distinguir mayúsculas (HTTP 409). */
public class CategoriaDuplicadaException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CategoriaDuplicadaException(String nombre) {
        super("Ya existe una categoría con el nombre «" + nombre + "»");
    }
}
