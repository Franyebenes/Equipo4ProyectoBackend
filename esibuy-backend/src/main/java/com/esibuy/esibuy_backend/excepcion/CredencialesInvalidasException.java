package com.esibuy.esibuy_backend.excepcion;

/**
 * El inicio de sesion se rechaza por credenciales invalidas. Es la misma excepcion, con el mismo mensaje, sin causa y
 * sin datos del usuario, tanto si el correo no existe, como si la contrasena no coincide o la cuenta no esta activa,
 * para que la respuesta (un 401 generico) no permita saber cual fue el motivo.
 */
public class CredencialesInvalidasException extends RuntimeException {

    public CredencialesInvalidasException() {
        super("Credenciales invalidas");
    }
}
