package com.esibuy.esibuy_backend.dto;

import com.esibuy.esibuy_backend.util.Constantes;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Datos de una direccion. El codigo postal debe ser espanol (01000-52999).
 * predeterminada solo se tiene en cuenta al crear; para cambiarla despues hay un endpoint propio.
 */
public record SolicitudDireccionDTO(
        @NotBlank @Size(max = 100) @Pattern(regexp = Constantes.PATRON_TEXTO_SEGURO) String destinatario,
        @NotBlank @Size(max = 100) @Pattern(regexp = Constantes.PATRON_TEXTO_SEGURO) String calle,
        @NotBlank @Size(max = 10) @Pattern(regexp = Constantes.PATRON_TEXTO_SEGURO) String numero,
        @Size(max = 30) @Pattern(regexp = Constantes.PATRON_TEXTO_SEGURO) String pisoPuerta,
        @NotBlank @Pattern(regexp = "^(0[1-9]|[1-4][0-9]|5[0-2])[0-9]{3}$") String codigoPostal,
        @NotBlank @Size(max = 80) @Pattern(regexp = Constantes.PATRON_TEXTO_SEGURO) String ciudad,
        @NotBlank @Size(max = 80) @Pattern(regexp = Constantes.PATRON_TEXTO_SEGURO) String provincia,
        boolean predeterminada) {
}