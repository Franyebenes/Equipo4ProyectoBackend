package com.esibuy.esibuy_backend.dto;

import java.util.List;

/** Fila de la tabla de productos del administrador .  */
public record ProductoAdminDTO(
        String id,
        String nombre,
        String vendedor,
        List<String> categorias,
        Double precio,
        int stock,
        boolean visible,
        boolean soloPremium) {
}