package com.esibuy.esibuy_backend.excepcion;

// Una IP ha agotado sus intentos de restablecer la contrasena. Es un 429. Sin datos: ni el token ni la IP van en el mensaje.
public class DemasiadosIntentosRestablecimientoException extends RuntimeException {

    public DemasiadosIntentosRestablecimientoException() {
        super("Demasiados intentos de restablecimiento");
    }
}
