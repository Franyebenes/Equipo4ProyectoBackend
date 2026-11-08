package com.esibuy.esibuy_backend.dto;

import java.util.List;

/**
 * Tarjeta de un producto en el panel del administrador. precio es el precio sin descuentos y precioFinal, lo que paga
 * un cliente estandar. valoracionMedia es null si el producto aun no tiene valoraciones.
 */
public record ProductoAdminDTO(
        String id,
        String nombre,
        String imagen,
        String vendedor,
        List<String> categorias,
        Double precio,
        Double precioFinal,
        int stock,
        boolean visible,
        boolean soloPremium,
        Double valoracionMedia,
        int numeroValoraciones) {
}
