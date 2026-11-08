package com.esibuy.esibuy_backend.excepcion;

// El segundo paso del login no tiene un desafio valido: no lo hay, caduco, ya se uso o la cuenta cambio entre los dos pasos. Es un 401 con siempre el mismo mensaje.
public class SegundoFactorNoVigenteException extends RuntimeException {

    public SegundoFactorNoVigenteException() {
        super("La verificacion en dos pasos no es valida o ha caducado");
    }
}
