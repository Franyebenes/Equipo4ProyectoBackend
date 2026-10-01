package com.esibuy.esibuy_backend.excepcion;

import java.util.Map;
import java.util.Set;

// Errores de validacion por campo. Sin causa y sin datos sensibles (la contrasena nunca va en el mensaje).

public class DatosRegistroInvalidosException extends RuntimeException {

    private final transient Map<String, Set<CodigoError>> errores;

    public DatosRegistroInvalidosException(Map<String, Set<CodigoError>> errores) {
        super("Los datos de registro no son validos");
        this.errores = errores;
    }

    public Map<String, Set<CodigoError>> getErrores() {
        return errores;
    }
}
