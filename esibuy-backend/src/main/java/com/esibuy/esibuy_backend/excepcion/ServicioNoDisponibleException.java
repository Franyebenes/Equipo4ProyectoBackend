package com.esibuy.esibuy_backend.excepcion;

public class ServicioNoDisponibleException extends RuntimeException {

    public ServicioNoDisponibleException(Throwable causa) {
        super("El servicio no esta disponible en este momento", causa);
    }
}
