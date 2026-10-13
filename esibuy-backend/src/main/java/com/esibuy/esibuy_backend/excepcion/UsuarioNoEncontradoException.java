package com.esibuy.esibuy_backend.excepcion;


public class UsuarioNoEncontradoException extends RuntimeException {

    /** Builds the exception with a fixed, non-sensitive message (it is only for logs; the client gets its own). */
    public UsuarioNoEncontradoException() {
        super("Usuario no encontrado");
    }
}
