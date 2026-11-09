package com.esibuy.esibuy_backend.controlador;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.esibuy.esibuy_backend.dto.PaginaDTO;
import com.esibuy.esibuy_backend.dto.ProductoAdminDTO;
import com.esibuy.esibuy_backend.dto.ProductoAdminDetalleDTO;
import com.esibuy.esibuy_backend.servicio.FiltroCatalogoCliente;
import com.esibuy.esibuy_backend.servicio.ServicioProductoAdmin;

/** HU-12 Modo lectura de productos. A proposito no hay POST, PUT, PATCH ni DELETE. */
@RestController
@RequestMapping("/api/admin/productos")
public class ControladorProductoAdmin {

    private final ServicioProductoAdmin servicio;

    public ControladorProductoAdmin(ServicioProductoAdmin servicio) {
        this.servicio = servicio;
    }

    // Mismos filtros que el catalogo de los clientes, todos opcionales y combinables
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public PaginaDTO<ProductoAdminDTO> listar(@RequestParam(required = false) String busqueda,
                                              @RequestParam(required = false) String idCategoria,
                                              @RequestParam(required = false) Double precioMinimo,
                                              @RequestParam(required = false) Double precioMaximo,
                                              @RequestParam(required = false) Integer valoracionMinima,
                                              @RequestParam(defaultValue = "false") boolean soloDisponibles,
                                              @RequestParam(defaultValue = "0") int pagina,
                                              @RequestParam(defaultValue = "12") int tamano) {
        FiltroCatalogoCliente filtro = new FiltroCatalogoCliente(busqueda, idCategoria, precioMinimo, precioMaximo,
                valoracionMinima, soloDisponibles);
        return servicio.listar(filtro, pagina, tamano);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ProductoAdminDetalleDTO obtener(@PathVariable String id) {
        return servicio.obtener(id);
    }
}
