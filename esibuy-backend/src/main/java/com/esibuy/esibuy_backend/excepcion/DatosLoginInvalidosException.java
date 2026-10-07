package com.esibuy.esibuy_backend.excepcion;

import java.util.Map;
import java.util.Set;

/**
 * Credenciales mal formadas (campo obligatorio vacio, formato o longitud no validos). Es un 400 y no cuenta como
 * intento fallido de inicio de sesion. Lleva los errores por campo, todos a la vez, con los mismos codigos que el
 * registro ({@link CodigoError}). Sin causa y sin la contrasena ni el correo en el mensaje.
 */
public class DatosLoginInvalidosException extends RuntimeException {

    private final transient Map<String, Set<CodigoError>> errores;

    public DatosLoginInvalidosException(Map<String, Set<CodigoError>> errores) {
        super("Los datos de inicio de sesion no son validos");
        this.errores = errores;
    }

    public Map<String, Set<CodigoError>> getErrores() {
        return errores;
    }
}
