package com.esibuy.esibuy_backend.dto;

/** Direccion a la que se envia el pedido, tal como la escribe el cliente al tramitarlo. */
public record DireccionEnvioDTO(String calle, String ciudad, String codigoPostal) {
}
