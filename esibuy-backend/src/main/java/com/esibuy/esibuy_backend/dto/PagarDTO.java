package com.esibuy.esibuy_backend.dto;

/** Peticion de pago (HU-23): con que tarjeta guardada se paga y a que direccion se envia. */
public record PagarDTO(String idMetodoPago, DireccionEnvioDTO direccionEnvio) {
}