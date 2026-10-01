package com.esibuy.esibuy_backend.excepcion;

/**
 * Registro rechazado por duplicado (email ya existente). Mensaje abstracto a proposito (decision D5):
 * no debe revelar si el email existe. Sin causa.
 */
public class RegistroNoCompletadoException extends RuntimeException {

    public static final String MENSAJE_GENERICO =
            "No se ha podido completar el registro con los datos proporcionados";

    public RegistroNoCompletadoException() {
        super(MENSAJE_GENERICO);
    }
}
