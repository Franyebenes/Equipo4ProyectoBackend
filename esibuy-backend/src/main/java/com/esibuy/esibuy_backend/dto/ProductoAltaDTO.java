package com.esibuy.esibuy_backend.dto;

import java.util.List;

/**
 * Datos para dar de alta un producto (HU15.1). El vendedor no viene aqui: es siempre el de la sesion.
 * activo y descuento son opcionales: sin activo el producto se crea activo y sin descuento no tiene rebaja.
 */
public record ProductoAltaDTO(
        String nombre,
        String descripcion,
        Double precio,
        List<String> idCategorias,
        Integer stock,
        String imagen,
        Boolean activo,
        Double descuento) {
}
