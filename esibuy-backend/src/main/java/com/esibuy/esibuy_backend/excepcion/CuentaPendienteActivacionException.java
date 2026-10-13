package com.esibuy.esibuy_backend.excepcion;

/**
 * Las credenciales son correctas pero la cuenta aun esta DESACTIVADO: un administrador debe activarla. Solo se lanza
 * cuando la contrasena es correcta, de modo que no permite saber que correos existen (quien no conoce la contrasena
 * recibe el mismo rechazo generico de siempre). Las cuentas BLOQUEADO siguen recibiendo ese rechazo generico.
 */
public class CuentaPendienteActivacionException extends RuntimeException {

    /** Codigo estable que el frontend usa para distinguir este caso. */
    public static final String CODIGO = "CUENTA_PENDIENTE_DE_ACTIVACION";
    public static final String MENSAJE = "Cuenta pendiente de activacion";

    public CuentaPendienteActivacionException() {
        super(MENSAJE);
    }
}
