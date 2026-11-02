package com.esibuy.esibuy_backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// : con que tarjeta guardada se paga (la de otro cliente da 404)
public record SolicitudPremiumDTO(@NotBlank @Size(max = 24) String idMetodoPago) {
}
