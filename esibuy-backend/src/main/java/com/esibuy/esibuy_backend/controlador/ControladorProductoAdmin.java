package com.esibuy.esibuy_backend.controlador;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.esibuy.esibuy_backend.dto.PaginaDTO;
import com.esibuy.esibuy_backend.dto.ProductoAdminDTO;
import com.esibuy.esibuy_backend.servicio.ServicioProductoAdmin;

/** HU-12 Modo lectura de productos. A proposito no hay POST, PUT, PATCH ni DELETE. */
@RestController
@RequestMapping("/api/admin/productos")
public class ControladorProductoAdmin {

    private final ServicioProductoAdmin servicio;

    public ControladorProductoAdmin(ServicioProductoAdmin servicio) {
        this.servicio = servicio;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public PaginaDTO<ProductoAdminDTO> listar(@RequestParam(defaultValue = "0") int pagina,
                                              @RequestParam(defaultValue = "20") int tamano) {
        return servicio.listar(pagina, tamano);
    }
}