package com.esibuy.esibuy_backend.servicio;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** La tarea programada vacia los carritos caducados con la hora actual. */
@ExtendWith(MockitoExtension.class)
class TareaCarritosCaducadosTest {

    @Mock
    private ServicioCarrito servicioCarrito;
    @InjectMocks
    private TareaCarritosCaducados tarea;

    @Test
    void alEjecutarseLiberaLosCarritosCaducados() {
        tarea.liberarCarritosCaducados();
        verify(servicioCarrito).liberarCarritosCaducados(any(Instant.class));
    }
}
