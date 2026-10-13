package com.esibuy.esibuy_backend.controlador;

import com.esibuy.esibuy_backend.dto.CategoriaDTO;
import com.esibuy.esibuy_backend.servicio.CatalogoAvatares;
import com.esibuy.esibuy_backend.servicio.ServicioCategoria;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Datos publicos de solo lectura que necesita el formulario de registro. Tests: CP-SEG-08. */
@RestController
@RequestMapping("/api/registro")
public class ControladorFormularioRegistro {

    private final CatalogoAvatares catalogoAvatares;
    private final ServicioCategoria servicioCategoria;

    public ControladorFormularioRegistro(CatalogoAvatares catalogoAvatares, ServicioCategoria servicioCategoria) {
        this.catalogoAvatares = catalogoAvatares;
        this.servicioCategoria = servicioCategoria;
    }

    @GetMapping("/avatares")
    public List<String> listarAvatares() {
        return catalogoAvatares.listarAvatares();
    }

    @GetMapping("/categorias")
    public List<CategoriaDTO> listarCategorias() {
        return servicioCategoria.listarCategorias();
    }
}
