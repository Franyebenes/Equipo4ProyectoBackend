package com.esibuy.esibuy_backend.dto;

// perfil ya estandar y cuantos productos solo Premium salieron del carrito
public record RespuestaBajaPremiumDTO(PerfilDTO perfil, int productosQuitadosDelCarrito) {
}
