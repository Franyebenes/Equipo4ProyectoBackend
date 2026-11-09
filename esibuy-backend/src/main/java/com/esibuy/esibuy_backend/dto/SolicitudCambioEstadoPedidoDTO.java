package com.esibuy.esibuy_backend.dto;

import java.time.LocalDate;

import com.esibuy.esibuy_backend.modelo.EstadoPedido;

/**
 * Nuevo estado al que el vendedor lleva uno de sus pedidos (PATCH /api/vendedor/pedidos/{id}/estado). Al enviarlo
 * (ENVIADO o EN_REPARTO) puede indicar tambien la fecha estimada de entrega; si no la indica, se calcula.
 */
public record SolicitudCambioEstadoPedidoDTO(EstadoPedido estado, LocalDate fechaEntregaEstimada) {
}
