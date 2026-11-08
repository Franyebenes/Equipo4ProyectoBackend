package com.esibuy.esibuy_backend.dto;

import java.time.Instant;

// perfil ya Premium y justificante ("•••• 4242")
public record RespuestaPremiumDTO(PerfilDTO perfil, double importe, String ultimosDigitos, Instant fecha) {
}
