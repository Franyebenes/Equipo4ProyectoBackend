package com.esibuy.esibuy_backend.servicio;

/**
 * Avisos de seguridad hacia el equipo de operacion. Detras de una interfaz para poder verificarlos en los tests.
 */
public interface AlertasSeguridad {

    /** Una IP ha probado muchos usuarios distintos en poco tiempo y pasa al modo adaptativo del limitador. */
    void ipEnModoAdaptativo(String ip, int usuariosDistintos);
}
