package com.esibuy.esibuy_backend.excepcion;

/**
 * La peticion es correcta pero choca con una regla de negocio (409).
 * El codigo permite al frontend y a los tests distinguir el motivo sin depender del texto.
 */
public class ConflictoNegocioException extends RuntimeException {

    private final String codigo;

    public ConflictoNegocioException(String codigo, String mensaje) {
        super(mensaje);
        this.codigo = codigo;
    }

    public String getCodigo() {
        return codigo;
    }
}