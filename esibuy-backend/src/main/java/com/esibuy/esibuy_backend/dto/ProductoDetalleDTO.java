package com.esibuy.esibuy_backend.dto;

import java.util.List;

/**
 * @param precio             precio base, sin descuentos
 * @param precioFinal        lo que paga este cliente: con el descuento para todos y, si es premium, tambien el premium
 * @param descuento          porcentaje de descuento para todos los clientes (null si no tiene)
 * @param descuentoPremium   porcentaje adicional para los clientes premium (null si no tiene)
 * @param valoracionMedia    media de las valoraciones (de 1 a 5), o null si todavia no tiene
 * @param numeroValoraciones cuantas valoraciones tiene
 */
public record ProductoDetalleDTO(String id, String nombre, String descripcion, String imagen, List<String> categorias,
                                 Double precio, Double precioFinal, Double descuento, Double descuentoPremium,
                                 Boolean soloPremium, Disponibilidad disponibilidad, Double valoracionMedia,
                                 int numeroValoraciones) {
}
