package com.esibuy.esibuy_backend.excepcion;

import java.util.Map;
import java.util.Set;

/**
 * Credenciales mal formadas (campo obligatorio vacio, formato o longitud no validos). Es un 400 y no cuenta como
 * intento fallido de inicio de sesion. Sin causa y sin la contrasena ni el correo en el mensaje.
 */
public class DatosLoginInvalidosException extends DatosInvalidosException {

    public DatosLoginInvalidosException(Map<String, Set<CodigoError>> errores) {
        super("Los datos de inicio de sesion no son validos", errores);
    }
}
