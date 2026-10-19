package com.esibuy.esibuy_backend.dto;

/** Peticion para anadir un producto al carrito: que producto y cuantas unidades. */
public record AnadirCarritoDTO(String idProducto, Integer cantidad) {
}