package com.esibuy.esibuy_backend.excepcion;

import java.util.Map;
import java.util.Set;

/*
 * Restablecimiento rechazado con errores por campo (es un 400): el enlace no es valido (campo token, siempre el mismo
 * codigo ENLACE_INVALIDO sea cual sea el motivo) o la contrasena nueva incumple la politica (campo contrasena, todos los
 * incumplimientos a la vez). Sin causa y sin el token ni la contrasena en el mensaje.
 */
public class DatosRestablecimientoInvalidosException extends DatosInvalidosException {

    public DatosRestablecimientoInvalidosException(Map<String, Set<CodigoError>> errores) {
        super("Los datos del restablecimiento de contrasena no son validos", errores);
    }
}
