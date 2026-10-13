package com.esibuy.esibuy_backend.servicio;

import com.esibuy.esibuy_backend.dto.CategoriaDTO;

import java.util.List;

public interface ServicioCategoria {

    List<CategoriaDTO> listarCategorias();
    CategoriaDTO obtenerCategoria(String id);
    CategoriaDTO crearCategoria(CategoriaDTO dto);
    CategoriaDTO modificarCategoria(String id, CategoriaDTO dto);
    void eliminarCategoria(String id);
}
