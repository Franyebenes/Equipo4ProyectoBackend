package com.esibuy.esibuy_backend.dto;

// disponible = visible y, si es solo Premium, que el cliente sea Premium. imagen en base64, o null si no tiene
public record ProductoEnListaDTO(String id, String nombre, String imagen, Double precio, boolean disponible) {
}
