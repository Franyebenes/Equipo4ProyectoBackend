package com.esibuy.esibuy_backend.dto;

import java.time.LocalDate;

import com.esibuy.esibuy_backend.util.Constantes;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Campos que el usuario puede editar. No hay email ni dni: no se pueden cambiar desde aqui.
 * fechaNacimiento solo se aplica a clientes y nombreComercial solo a vendedores.
 */
public record SolicitudModificacionPerfilDTO(
        @NotBlank @Size(max = 50) @Pattern(regexp = Constantes.PATRON_TEXTO_SEGURO) String nombre,
        @NotBlank @Size(max = 100) @Pattern(regexp = Constantes.PATRON_TEXTO_SEGURO) String apellidos,
        @Pattern(regexp = "^([0-9]{9})?$") String telefono,
        LocalDate fechaNacimiento,
        @NotBlank @Size(max = 40) String avatar,
        @Size(max = 80) @Pattern(regexp = Constantes.PATRON_TEXTO_SEGURO) String nombreComercial) {
}