package com.esibuy.esibuy_backend.util;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

// Reloj de pruebas que solo avanza cuando el test lo pide, para comprobar bloqueos y ventanas de tiempo sin esperar.
 
public class RelojMutable extends Clock {

    private final AtomicReference<Instant> ahora;
    private final ZoneId zona;

    public RelojMutable(Instant inicio, ZoneId zona) {
        this.ahora = new AtomicReference<>(inicio);
        this.zona = zona;
    }

    public void avanzar(Duration duracion) {
        ahora.updateAndGet(instante -> instante.plus(duracion));
    }

    @Override
    public ZoneId getZone() {
        return zona;
    }

    @Override
    public Clock withZone(ZoneId nuevaZona) {
        return new RelojMutable(ahora.get(), nuevaZona);
    }

    @Override
    public Instant instant() {
        return ahora.get();
    }
}
