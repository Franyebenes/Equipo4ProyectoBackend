package com.esibuy.esibuy_backend.excepcion;

/**
 * No se pudo entregar un correo al servidor SMTP (caido, rechazo, tiempo agotado o certificado no valido). El mensaje es
 * fijo y nunca incluye el cuerpo del correo ni credenciales; la causa original se conserva solo para el diagnostico.
 */
public class FalloEnvioCorreoException extends RuntimeException {

    public FalloEnvioCorreoException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
