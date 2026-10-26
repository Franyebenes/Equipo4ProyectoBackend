package com.esibuy.esibuy_backend.excepcion;

import java.util.Map;
import java.util.Set;

/*
 * Operacion del segundo factor rechazada con errores por campo (es un 400): un campo obligatorio vacio (OBLIGATORIO), la
 * contrasena que no es la de la cuenta (CONTRASENA_INCORRECTA), el codigo que no vale (CODIGO_INCORRECTO) o la activacion
 * que no esta iniciada o ha caducado (ACTIVACION_NO_VIGENTE). Sin causa y sin datos sensibles: ni la contrasena ni el
 * codigo van en el mensaje.
 */
public class DatosDosFactoresInvalidosException extends DatosInvalidosException {

    public DatosDosFactoresInvalidosException(Map<String, Set<CodigoError>> errores) {
        super("Los datos de la operacion del segundo factor no son validos", errores);
    }
}
