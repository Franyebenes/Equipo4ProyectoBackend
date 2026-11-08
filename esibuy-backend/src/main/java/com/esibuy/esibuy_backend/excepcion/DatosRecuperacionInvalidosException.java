package com.esibuy.esibuy_backend.excepcion;

import java.util.Map;
import java.util.Set;

/*
 * Solicitud de recuperacion mal formada (correo vacio, con formato no valido o demasiado largo). Es un 400. Depende
 * solo del texto recibido, nunca de si la cuenta existe. Sin causa y sin el correo en el mensaje.
 */
public class DatosRecuperacionInvalidosException extends DatosInvalidosException {

    public DatosRecuperacionInvalidosException(Map<String, Set<CodigoError>> errores) {
        super("Los datos de la solicitud de recuperacion no son validos", errores);
    }
}
