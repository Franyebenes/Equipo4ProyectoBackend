package com.esibuy.esibuy_backend.dto;

import java.util.List;

/**
 * Ficha de un producto para el administrador (solo lectura). precio es el precio sin descuentos; precioFinal, lo que
 * paga un cliente estandar y precioPremium, lo que paga un cliente premium. descuento y descuentoPremium son
 * porcentajes y pueden no existir.
 */
public record ProductoAdminDetalleDTO(
        String id,
        String nombre,
        String descripcion,
        String imagen,
        String vendedor,
        List<String> categorias,
        Double precio,
        Double precioFinal,
        Double precioPremium,
        Double descuento,
        Double descuentoPremium,
        int stock,
        boolean visible,
        boolean soloPremium,
        Double valoracionMedia,
        int numeroValoraciones) {
}
