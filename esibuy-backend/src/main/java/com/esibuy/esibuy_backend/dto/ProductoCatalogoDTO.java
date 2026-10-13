package com.esibuy.esibuy_backend.dto;

import java.util.List;

/**
 * Producto tal y como lo ve su vendedor en "Mi catalogo" (listado y detalle). Las categorias van por nombre; el
 * precio es el base y los descuentos son porcentajes (pueden ser null). "visible" indica si el producto esta activo.
 */
public record ProductoCatalogoDTO(
        String id,
        String nombre,
        String descripcion,
        String imagen,
        List<String> categorias,
        Double precio,
        Double descuento,
        Double descuentoPremium,
        Integer stock,
        Boolean visible) {
}
