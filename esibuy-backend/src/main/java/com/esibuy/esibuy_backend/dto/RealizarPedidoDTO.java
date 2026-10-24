package com.esibuy.esibuy_backend.dto;

/** Peticion para realizar un pedido con lo que hay en el carrito. El pago es simulado: solo hace falta la direccion. */
public record RealizarPedidoDTO(DireccionEnvioDTO direccionEnvio) {
}
