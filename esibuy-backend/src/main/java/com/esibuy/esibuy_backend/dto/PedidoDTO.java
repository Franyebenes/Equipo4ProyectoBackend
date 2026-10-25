package com.esibuy.esibuy_backend.dto;

import java.time.Instant;
import java.util.List;

import com.esibuy.esibuy_backend.modelo.EstadoPedido;

/** Pedido del cliente con sus lineas, para el historial de compras. */
public record PedidoDTO(String id, EstadoPedido estado, Instant fecha, double precioTotal,
                        DireccionEnvioDTO direccionEnvio, List<LineaPedidoDTO> lineas) {
}
