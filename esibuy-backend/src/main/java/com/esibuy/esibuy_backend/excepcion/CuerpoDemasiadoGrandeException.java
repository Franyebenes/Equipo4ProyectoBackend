package com.esibuy.esibuy_backend.excepcion;

/** El cuerpo de la peticion supera el tamano maximo admitido (CP-SEG-11). */
public class CuerpoDemasiadoGrandeException extends RuntimeException {

    public CuerpoDemasiadoGrandeException() {
        super("El cuerpo de la peticion supera el tamano maximo admitido");
    }
}
