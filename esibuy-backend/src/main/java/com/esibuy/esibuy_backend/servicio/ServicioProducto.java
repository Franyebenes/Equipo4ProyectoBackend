package com.esibuy.esibuy_backend.servicio;

import java.util.List;

import com.esibuy.esibuy_backend.dto.ProductoAltaDTO;
import com.esibuy.esibuy_backend.dto.ProductoCatalogoDTO;
import com.esibuy.esibuy_backend.dto.ProductoModificacionDTO;

public interface ServicioProducto {

    List<ProductoCatalogoDTO> listarProductosDelVendedor(String idVendedor, FiltroCatalogo filtro);
    ProductoCatalogoDTO obtenerProductoDelVendedor(String idVendedor, String idProducto);
    ProductoCatalogoDTO crearProducto(String idVendedor, ProductoAltaDTO datos);
    ProductoCatalogoDTO cambiarEstado(String idVendedor, String idProducto, Boolean activo);
    ProductoCatalogoDTO cambiarStock(String idVendedor, String idProducto, Integer stock);
    void eliminarProducto(String idVendedor, String idProducto);
    ProductoCatalogoDTO modificarProducto(String idVendedor, String idProducto, ProductoModificacionDTO datos);
}
