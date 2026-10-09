package com.esibuy.esibuy_backend.excepcion;

import java.util.Map;
import java.util.Set;

/** Datos de registro no validos: errores de validacion por campo, sin causa y sin datos sensibles. */
public class DatosRegistroInvalidosException extends DatosInvalidosException {

    public DatosRegistroInvalidosException(Map<String, Set<CodigoError>> errores) {
        super("Los datos de registro no son validos", errores);
    }
}
