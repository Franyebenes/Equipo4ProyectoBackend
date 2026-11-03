package com.esibuy.esibuy_backend.dto;

import java.util.List;

/**
 * Datos para dar de alta un producto (HU15.1) y para modificarlo (Modificar producto), con las mismas validaciones.
 * El vendedor no viene aqui: es siempre el de la sesion.
 * activo y descuento son opcionales: sin descuento no tiene rebaja; sin activo, en el alta se crea activo y al
 * modificarlo conserva su estado.
 * descuentoPremium y soloPremium (sprint 2) tambien son opcionales: sin descuentoPremium los premium pagan como el
 * resto; sin soloPremium, en el alta lo ven todos los clientes y al modificarlo conserva su valor.
 */
public record ProductoAltaDTO(
        String nombre,
        String descripcion,
        Double precio,
        List<String> idCategorias,
        Integer stock,
        String imagen,
        Boolean activo,
        Double descuento,
        Double descuentoPremium,
        Boolean soloPremium) {
}
