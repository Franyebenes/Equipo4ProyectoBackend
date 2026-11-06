package com.esibuy.esibuy_backend.dto;

import com.esibuy.esibuy_backend.modelo.EstadoPedido;

/** Nuevo estado al que el vendedor lleva uno de sus pedidos (PATCH /api/vendedor/pedidos/{id}/estado). */
public record SolicitudCambioEstadoPedidoDTO(EstadoPedido estado) {
}
