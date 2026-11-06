package com.esibuy.esibuy_backend.modelo;

import java.time.Instant;

import org.springframework.data.mongodb.core.mapping.Field;

/**
 * Un paso del seguimiento de un pedido (Estado del envio): a que estado paso y cuando. Se guarda dentro del pedido,
 * en la lista seguimiento, en el orden en que ocurrieron.
 */
public class EventoSeguimiento {

    @Field("estado")
    private EstadoPedido estado;
    @Field("fecha")
    private Instant fecha;

    /** Usado por Spring Data al leer de MongoDB. */
    protected EventoSeguimiento() {
    }

    public EventoSeguimiento(EstadoPedido estado, Instant fecha) {
        this.estado = estado;
        this.fecha = fecha;
    }

    public EstadoPedido getEstado() {
        return estado;
    }

    public Instant getFecha() {
        return fecha;
    }
}
