package com.esibuy.esibuy_backend.servicio;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.esibuy.esibuy_backend.excepcion.CodigoError;
import com.esibuy.esibuy_backend.excepcion.DatosRegistroInvalidosException;

final class ErroresRegistro {
// Acumula los errores de validacion por campo para devolverlos todos a la vez
    private final Map<String, Set<CodigoError>> errores = new LinkedHashMap<>();

    void anadir(String campo, CodigoError codigo) {
        errores.computeIfAbsent(campo, c -> EnumSet.noneOf(CodigoError.class)).add(codigo);
    }

    void anadirTodos(String campo, Set<CodigoError> codigos) {
        codigos.forEach(codigo -> anadir(campo, codigo));
    }

    /** Lanza DatosRegistroInvalidosException si se ha acumulado algun error. */
    void lanzarSiHay() {
        if (!errores.isEmpty()) {
            throw new DatosRegistroInvalidosException(Collections.unmodifiableMap(errores));
        }
    }
}
