package com.esibuy.esibuy_backend.servicio;

import java.time.Instant;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Carritos abandonados: nada mas arrancar el servidor y despues cada hora se vacian los que llevan mas de 24 horas sin
// tocarse y sus unidades vuelven al stock. Se compara con la fecha guardada en cada carrito, asi que tambien caducan
// los que se dejaron con el servidor apagado.
@Component
public class TareaCarritosCaducados {

    private static final long AL_ARRANCAR_MS = 0;
    private static final long CADA_HORA_MS = 60L * 60 * 1000;

    private final ServicioCarrito servicioCarrito;

    public TareaCarritosCaducados(ServicioCarrito servicioCarrito) {
        this.servicioCarrito = servicioCarrito;
    }

    @Scheduled(initialDelay = AL_ARRANCAR_MS, fixedRate = CADA_HORA_MS)
    public void liberarCarritosCaducados() {
        servicioCarrito.liberarCarritosCaducados(Instant.now());
    }
}
