package com.esibuy.esibuy_backend.excepcion;

// Una cuenta o una IP ha agotado sus fallos al operar con el segundo factor. Es un 429. Sin datos: ni la cuenta ni la IP van en el mensaje.
public class DemasiadosIntentosDosFactoresException extends RuntimeException {

    public DemasiadosIntentosDosFactoresException() {
        super("Demasiados fallos al verificar el segundo factor");
    }
}
