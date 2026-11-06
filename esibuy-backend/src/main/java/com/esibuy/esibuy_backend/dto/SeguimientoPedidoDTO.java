package com.esibuy.esibuy_backend.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.esibuy.esibuy_backend.modelo.EstadoPedido;

/**
 * Estado del envio de un pedido, tal y como lo ve el cliente (GET /api/pedidos/{id}/seguimiento):
 * <ul>
 *   <li>estado y fechaActualizacion: el estado mas reciente y cuando se registro.</li>
 *   <li>fechaEntregaEstimada: mientras va de camino; null si ya se entrego o se cancelo.</li>
 *   <li>fechaEntrega: cuando se entrego, si ya se entrego o el cliente confirmo que lo recibio.</li>
 *   <li>disponible: false si no hay seguimiento que mostrar (pedido cancelado); entonces pasos va vacio.</li>
 *   <li>pasos: los del envio en orden (pagado, en preparacion, enviado, en reparto y entregado).</li>
 * </ul>
 */
public record SeguimientoPedidoDTO(String id, EstadoPedido estado, Instant fecha, Instant fechaActualizacion,
                                   LocalDate fechaEntregaEstimada, Instant fechaEntrega, boolean disponible,
                                   List<PasoSeguimientoDTO> pasos) {
}
