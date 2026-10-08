package com.esibuy.esibuy_backend.excepcion;

/*
 * El inicio de sesion esta bloqueado de forma temporal (cuenta bloqueada tras varios fallos o limite de intentos por
 * IP). Lleva los segundos que faltan para poder intentarlo de nuevo, que la capa web devuelve en Retry-After.
 * El mensaje no incluye ni el correo ni la IP.
 */
public class LoginBloqueadoTemporalmenteException extends RuntimeException {

    private final long segundosRestantes;

    public LoginBloqueadoTemporalmenteException(long segundosRestantes) {
        super("Inicio de sesion bloqueado temporalmente");
        this.segundosRestantes = segundosRestantes;
    }

    public long getSegundosRestantes() {
        return segundosRestantes;
    }
}
