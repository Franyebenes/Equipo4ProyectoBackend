package com.esibuy.esibuy_backend.dto;

import java.time.Instant;
import java.util.List;

import com.esibuy.esibuy_backend.modelo.EstadoPedido;

/**
 * Detalle de un pedido recibido por el vendedor: los mismos datos que en el listado mas la direccion a la que hay que
 * enviarlo y sus lineas.
 */
public record PedidoRecibidoDetalleDTO(String id, Instant fecha, String cliente, double importe, EstadoPedido estado,
                                       int unidades, DireccionEnvioDTO direccionEnvio,
                                       List<LineaPedidoRecibidoDTO> lineas) {
}
