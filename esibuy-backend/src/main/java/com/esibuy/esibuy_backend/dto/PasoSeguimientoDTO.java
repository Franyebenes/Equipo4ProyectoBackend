package com.esibuy.esibuy_backend.dto;

import java.time.Instant;

import com.esibuy.esibuy_backend.modelo.EstadoPedido;

/**
 * Un paso del envio en el seguimiento que ve el cliente: si ya se ha alcanzado (completado), si es en el que esta
 * ahora el pedido (actual) y cuando se alcanzo (null si aun no ha llegado o si no se sabe, en pedidos antiguos).
 */
public record PasoSeguimientoDTO(EstadoPedido estado, boolean completado, boolean actual, Instant fecha) {
}
