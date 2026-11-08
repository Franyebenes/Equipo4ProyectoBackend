package com.esibuy.esibuy_backend.dto;

import java.util.List;

// una lista con los datos de sus productos
public record ListaDetalleDTO(String id, String nombre, boolean porDefecto, List<ProductoEnListaDTO> productos) {
}
