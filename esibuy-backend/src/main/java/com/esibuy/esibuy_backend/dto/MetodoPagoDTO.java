package com.esibuy.esibuy_backend.dto;

/** Metodo de pago tal como lo ve el cliente: el frontend lo muestra como "•••• 1234". */
public record MetodoPagoDTO(String id, String titular, String ultimosDigitos, String caducidad) {
}