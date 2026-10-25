package com.esibuy.esibuy_backend.excepcion;

/** Los filtros del catalogo no son validos, por ejemplo un precio minimo mayor que el maximo (HTTP 400). */
public class FiltroNoValidoException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public FiltroNoValidoException(String mensaje) {
        super(mensaje);
    }
}
