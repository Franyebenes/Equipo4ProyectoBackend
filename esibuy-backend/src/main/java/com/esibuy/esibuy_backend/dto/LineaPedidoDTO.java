package com.esibuy.esibuy_backend.dto;

/**
 * Una linea del pedido tal como la ve el cliente en su historial. precioUnitario es el que pago por cada unidad.
 * valoracion son las estrellas (1-5) que dio al producto en este pedido, o null si aun no lo ha valorado.
 */
public record LineaPedidoDTO(String idProducto, String nombre, int cantidad, double precioUnitario, double subtotal,
                             Integer valoracion) {
}
