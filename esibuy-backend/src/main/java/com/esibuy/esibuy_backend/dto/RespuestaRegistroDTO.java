package com.esibuy.esibuy_backend.dto;

/** Nunca debe incluir contrasena ni hash (CP-REG-08, CP-CTR-01). */
public record RespuestaRegistroDTO(String id, String email, String nombre, String mensaje) {
}
