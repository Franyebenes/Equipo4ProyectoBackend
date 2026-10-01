package com.esibuy.esibuy_backend.excepcion;

/** Un servicio externo (p. ej. la comprobacion de dominio del email) no responde. */
public class ServicioNoDisponibleException extends RuntimeException {

    public ServicioNoDisponibleException(Throwable causa) {
        // El mensaje no debe revelar la causa interna (CP-REG-25)
        super("El servicio no esta disponible en este momento", causa);
    }
}
