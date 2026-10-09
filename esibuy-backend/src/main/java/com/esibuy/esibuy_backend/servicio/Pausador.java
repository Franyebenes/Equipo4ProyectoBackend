package com.esibuy.esibuy_backend.servicio;

import java.time.Duration;

/**
 * Espera una duracion. Existe para que el retraso fijo del modo adaptativo del limitador de login se pueda
 * sustituir en los tests por un mock y que ninguna prueba espere tiempo real.
 */
public interface Pausador {

    void pausar(Duration duracion);
}
