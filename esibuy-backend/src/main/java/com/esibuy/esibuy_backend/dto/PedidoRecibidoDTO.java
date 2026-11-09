package com.esibuy.esibuy_backend.dto;

import java.time.Instant;

import com.esibuy.esibuy_backend.modelo.EstadoPedido;

/**
 * Un pedido en el listado de pedidos recibidos del vendedor: lo basico para identificarlo. cliente es el nombre y
 * apellidos del cliente (o su email si no los tiene) e importe, lo que pago por todo el pedido. unidades es la suma de
 * las cantidades de todas sus lineas.
 */
public record PedidoRecibidoDTO(String id, Instant fecha, String cliente, double importe, EstadoPedido estado,
                                int unidades) {
}
