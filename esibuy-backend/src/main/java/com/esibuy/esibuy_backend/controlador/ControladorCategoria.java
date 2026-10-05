package com.esibuy.esibuy_backend.controlador;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.esibuy.esibuy_backend.dto.CategoriaDTO;
import com.esibuy.esibuy_backend.servicio.CategoriaService;

@RestController
@RequestMapping("/api/admin/categorias")
public class ControladorCategoria {

    private final CategoriaService servicioCategoria;

    public ControladorCategoria(CategoriaService servicioCategoria) {
        this.servicioCategoria = servicioCategoria;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<CategoriaDTO>> listarCategorias() {
        return ResponseEntity.ok(servicioCategoria.listarCategorias());
    }
}