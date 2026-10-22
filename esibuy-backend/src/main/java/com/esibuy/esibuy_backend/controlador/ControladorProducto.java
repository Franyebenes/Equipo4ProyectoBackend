package com.esibuy.esibuy_backend.controlador;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.esibuy.esibuy_backend.dto.EstadoProductoDTO;
import com.esibuy.esibuy_backend.dto.ProductoAltaDTO;
import com.esibuy.esibuy_backend.dto.ProductoCatalogoDTO;
import com.esibuy.esibuy_backend.dto.StockProductoDTO;
import com.esibuy.esibuy_backend.servicio.FiltroCatalogo;
import com.esibuy.esibuy_backend.servicio.ServicioProducto;

/**
 * Catalogo propio del vendedor. Solo para el rol VENDEDOR (regla de /api/vendedor/** en ConfiguracionSeguridad).
 * El vendedor es siempre el de la sesion (el principal es su id): nunca se recibe por parametro, asi que nadie puede
 * pedir el catalogo de otro.
 */
@RestController
@RequestMapping("/api/vendedor/productos")
public class ControladorProducto {

    private static final String RUTA_ID = "/{id}";

    private final ServicioProducto servicioProducto;

    public ControladorProducto(ServicioProducto servicioProducto) {
        this.servicioProducto = servicioProducto;
    }

    // Buscar y filtrar el catalogo propio. Todos los parametros son opcionales y se pueden combinar: busqueda (nombre
    // completo o parcial), idCategoria, activo (true/false) y rango de precio. Sin parametros, todo el catalogo.
    @GetMapping
    @PreAuthorize("hasRole('VENDEDOR')")
    public ResponseEntity<List<ProductoCatalogoDTO>> listarMisProductos(
            Authentication autenticacion,
            @RequestParam(required = false) String busqueda,
            @RequestParam(required = false) String idCategoria,
            @RequestParam(required = false) Boolean activo,
            @RequestParam(required = false) Double precioMinimo,
            @RequestParam(required = false) Double precioMaximo) {
        FiltroCatalogo filtro = new FiltroCatalogo(busqueda, idCategoria, activo, precioMinimo, precioMaximo);
        return ResponseEntity.ok(servicioProducto.listarProductosDelVendedor(autenticacion.getName(), filtro));
    }

    @GetMapping(RUTA_ID)
    @PreAuthorize("hasRole('VENDEDOR')")
    public ResponseEntity<ProductoCatalogoDTO> obtenerMiProducto(@PathVariable String id, Authentication autenticacion) {
        return ResponseEntity.ok(servicioProducto.obtenerProductoDelVendedor(autenticacion.getName(), id));
    }

    // HU15.1 - Alta de producto en el catalogo propio del vendedor
    @PostMapping
    @PreAuthorize("hasRole('VENDEDOR')")
    public ResponseEntity<ProductoCatalogoDTO> crearProducto(@RequestBody ProductoAltaDTO datos, Authentication autenticacion) {
        return ResponseEntity.status(HttpStatus.CREATED).body(servicioProducto.crearProducto(autenticacion.getName(), datos));
    }

    // Activar/Desactivar producto: {"activo": false} lo oculta a los clientes sin borrarlo y {"activo": true} lo
    // vuelve a mostrar. Solo productos del vendedor de la sesion (si no, 404); exige token CSRF.
    @PatchMapping(RUTA_ID + "/estado")
    @PreAuthorize("hasRole('VENDEDOR')")
    public ResponseEntity<ProductoCatalogoDTO> cambiarEstado(@PathVariable String id,
                                                             @RequestBody EstadoProductoDTO estado,
                                                             Authentication autenticacion) {
        return ResponseEntity.ok(servicioProducto.cambiarEstado(autenticacion.getName(), id, estado.activo()));
    }

    // Gestion de stock: {"stock": 12} fija las unidades disponibles (entero no negativo; con 0 deja de estar a la
    // venta). Solo productos del vendedor de la sesion (si no, 404); exige token CSRF.
    @PatchMapping(RUTA_ID + "/stock")
    @PreAuthorize("hasRole('VENDEDOR')")
    public ResponseEntity<ProductoCatalogoDTO> cambiarStock(@PathVariable String id,
                                                            @RequestBody StockProductoDTO stock,
                                                            Authentication autenticacion) {
        return ResponseEntity.ok(servicioProducto.cambiarStock(autenticacion.getName(), id, stock.stock()));
    }
}
