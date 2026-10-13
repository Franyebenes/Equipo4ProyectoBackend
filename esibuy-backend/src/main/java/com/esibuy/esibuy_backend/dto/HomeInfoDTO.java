package com.esibuy.esibuy_backend.dto;

import java.util.List;

public record HomeInfoDTO(
        String nombre,
        String descripcion,
        String version,
        List<String> caracteristicas,
        ContactoDTO contacto
) {
}