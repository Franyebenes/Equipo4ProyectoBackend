package com.esibuy.esibuy_backend.servicio;

import java.util.List;

import com.esibuy.esibuy_backend.dto.ProductoAltaDTO;
import com.esibuy.esibuy_backend.dto.ProductoCatalogoDTO;

public interface ServicioProducto {

    List<ProductoCatalogoDTO> listarProductosDelVendedor(String idVendedor);
    ProductoCatalogoDTO obtenerProductoDelVendedor(String idVendedor, String idProducto);
    ProductoCatalogoDTO crearProducto(String idVendedor, ProductoAltaDTO datos);
}
