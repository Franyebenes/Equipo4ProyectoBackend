package com.esibuy.esibuy_backend.dto;

import java.util.List;

/** Carrito del cliente: sus lineas, cuantas unidades hay en total y el importe total (solo de lo disponible). */
public record CarritoDTO(List<LineaCarritoDTO> lineas, int numeroArticulos, double total) {

    public static CarritoDTO vacio() {
        return new CarritoDTO(List.of(), 0, 0.0);
    }
}