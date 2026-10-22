package com.esibuy.esibuy_backend.dto;

import java.util.List;

/**
 * Datos que el vendedor puede editar de uno de sus productos (Modificar producto). Sustituyen a los guardados: sin
 * descuento el producto queda sin rebaja y sin imagen queda sin foto. El stock y el estado no van aqui porque se
 * cambian en sus propios endpoints, y el vendedor tampoco: es siempre el de la sesion.
 */
public record ProductoModificacionDTO(
        String nombre,
        String descripcion,
        Double precio,
        Double descuento,
        List<String> idCategorias,
        String imagen) {
}
