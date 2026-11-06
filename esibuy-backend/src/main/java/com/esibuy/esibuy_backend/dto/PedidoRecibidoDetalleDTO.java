package com.esibuy.esibuy_backend.dto;

import java.time.Instant;
import java.util.List;

import com.esibuy.esibuy_backend.modelo.EstadoPedido;

/**
 * Detalle de un pedido recibido por el vendedor: los mismos datos que en el listado mas la direccion a la que hay que
 * enviarlo y sus lineas. fechaActualizacion es la ultima vez que cambio su estado y estadosSiguientes, los estados a
 * los que el vendedor lo puede llevar ahora (vacio si el pedido ya no admite cambios).
 */
public record PedidoRecibidoDetalleDTO(String id, Instant fecha, String cliente, double importe, EstadoPedido estado,
                                       int unidades, Instant fechaActualizacion,
                                       List<EstadoPedido> estadosSiguientes, DireccionEnvioDTO direccionEnvio,
                                       List<LineaPedidoRecibidoDTO> lineas) {
}
