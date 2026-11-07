package com.esibuy.esibuy_backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import com.esibuy.esibuy_backend.util.Constantes;

// nombre de una lista (crear o renombrar). Solo letras, numeros y espacios
public record SolicitudListaDTO(
        @NotBlank @Size(max = 40) @Pattern(regexp = Constantes.PATRON_NOMBRE) String nombre) {
}
