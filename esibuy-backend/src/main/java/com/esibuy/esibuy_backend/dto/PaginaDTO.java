package com.esibuy.esibuy_backend.dto;

import java.util.List;

import org.springframework.data.domain.Page;

// JSON envelope for paginated lists.
public record PaginaDTO<T>(List<T> contenido, int pagina, int tamano, long totalElementos, int totalPaginas) {

    public static <T> PaginaDTO<T> desde(Page<T> page) {
        return new PaginaDTO<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(),
                page.getTotalPages());
    }
}
