package com.esibuy.esibuy_backend.excepcion;

/**
 * El recurso no existe o no pertenece al usuario de la sesion.
 * Se usa el mismo mensaje en los dos casos para no revelar recursos ajenos.
 */
public class RecursoNoEncontradoException extends RuntimeException {

    public RecursoNoEncontradoException(String mensaje) {
        super(mensaje);
    }
}