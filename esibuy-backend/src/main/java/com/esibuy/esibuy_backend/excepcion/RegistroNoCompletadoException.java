package com.esibuy.esibuy_backend.excepcion;

public class RegistroNoCompletadoException extends RuntimeException {

    public static final String MENSAJE_GENERICO =
            "No se ha podido completar el registro con los datos proporcionados";

    public RegistroNoCompletadoException() {
        super(MENSAJE_GENERICO);
    }
}
