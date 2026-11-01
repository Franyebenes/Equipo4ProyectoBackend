package com.esibuy.esibuy_backend.dto;

/** Resultado del pago: el pedido creado (ya PAGADO) y la tarjeta con la que se pago ("•••• 4242"). */
public record ResultadoPagoDTO(PedidoDTO pedido, String ultimosDigitos) {
}