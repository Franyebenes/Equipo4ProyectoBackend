package com.esibuy.esibuy_backend.dto;

import java.util.List;

/**
 * @param precio      precio base, sin descuentos (se muestra tachado si hay descuento)
 * @param precioFinal lo que paga este cliente: con el descuento para todos y, si es premium, tambien el premium
 * @param soloPremium producto solo para clientes premium (al resto se le muestra, pero no lo puede comprar)
 */
public record ProductoClienteDTO(String id, String nombre, String imagen, List<String> categorias, Double precio,
                                 Double precioFinal, Boolean soloPremium, Disponibilidad disponibilidad) {
}