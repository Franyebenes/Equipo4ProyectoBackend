package com.esibuy.esibuy_backend.dto;

/**
 * Una linea del carrito tal como la ve el cliente. precioUnitario ya lleva el descuento aplicado; precioOriginal es el
 * precio base, para mostrarlo tachado. Si el vendedor ha desactivado el producto, disponible es false y no cuenta en
 * el total. idVendedor y nombreVendedor sirven para agrupar el carrito por vendedor: cada compra es de uno solo.
 */
public record LineaCarritoDTO(String idProducto, String nombre, String imagen, double precioUnitario,
                              double precioOriginal, int cantidad, double subtotal, boolean disponible,
                              String idVendedor, String nombreVendedor) {

    /** Sin vendedor: se mantiene para el codigo y los tests anteriores. */
    public LineaCarritoDTO(String idProducto, String nombre, String imagen, double precioUnitario,
                           double precioOriginal, int cantidad, double subtotal, boolean disponible) {
        this(idProducto, nombre, imagen, precioUnitario, precioOriginal, cantidad, subtotal, disponible, null, null);
    }
}