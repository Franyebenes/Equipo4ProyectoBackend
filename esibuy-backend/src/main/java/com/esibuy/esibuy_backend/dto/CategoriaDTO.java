package com.esibuy.esibuy_backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CategoriaDTO(
        String id,

        @NotBlank(message = "El nombre de la categoría es obligatorio")
        @Size(max = 50, message = "El nombre no puede superar los 50 caracteres")
        String nombre,

        @NotBlank(message = "La descripción de la categoría es obligatoria")
        @Size(max = 200, message = "La descripción no puede superar los 200 caracteres")
        String descripcion) {
}