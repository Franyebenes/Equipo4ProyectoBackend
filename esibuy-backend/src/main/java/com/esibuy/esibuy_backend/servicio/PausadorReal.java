package com.esibuy.esibuy_backend.servicio;

import java.time.Duration;

import org.springframework.stereotype.Component;

//Pausador real: duerme el hilo de la peticion. Si lo interrumpen, conserva la marca de interrupcion.
@Component
public class PausadorReal implements Pausador {

    @Override
    public void pausar(Duration duracion) {
        try {
            Thread.sleep(duracion);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
