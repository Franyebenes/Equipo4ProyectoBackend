package com.esibuy.esibuy_backend.dto;

/**
 * Una linea del carrito tal como la ve el cliente. precioUnitario ya lleva el descuento aplicado; precioOriginal es el
 * precio base, para mostrarlo tachado. Si el vendedor ha desactivado el producto, disponible es false y no cuenta en
 * el total.
 */
public record LineaCarritoDTO(String idProducto, String nombre, String imagen, double precioUnitario,
                              double precioOriginal, int cantidad, double subtotal, boolean disponible) {
}