package com.esibuy.esibuy_backend.excepcion;

import java.util.Map;
import java.util.Set;

/**
 * Datos de entrada no validos, con los errores por campo (todos a la vez) y sus codigos ({@link CodigoError}). Es un
 * 400. Sin causa y sin datos sensibles: ni la contrasena ni el correo van en el mensaje. Base comun de
 * {@link DatosRegistroInvalidosException} y {@link DatosLoginInvalidosException}, que solo aportan su mensaje.
 */
public abstract class DatosInvalidosException extends RuntimeException {

    private final transient Map<String, Set<CodigoError>> errores;

    protected DatosInvalidosException(String mensaje, Map<String, Set<CodigoError>> errores) {
        super(mensaje);
        this.errores = errores;
    }

    public Map<String, Set<CodigoError>> getErrores() {
        return errores;
    }
}
