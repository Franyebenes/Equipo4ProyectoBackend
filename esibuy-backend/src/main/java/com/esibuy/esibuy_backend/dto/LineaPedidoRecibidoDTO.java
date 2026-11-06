package com.esibuy.esibuy_backend.dto;

/**
 * Una linea de un pedido recibido, tal y como la ve el vendedor: producto, unidades, precio que pago el cliente por
 * cada una (ya con su descuento) y el subtotal de la linea.
 */
public record LineaPedidoRecibidoDTO(String idProducto, String nombre, int cantidad, double precioUnitario,
                                     double subtotal) {
}
