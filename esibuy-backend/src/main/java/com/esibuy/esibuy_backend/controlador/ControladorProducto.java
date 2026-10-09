package com.esibuy.esibuy_backend.controlador;

import java.net.URI;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.esibuy.esibuy_backend.dto.ProductoAltaDTO;
import com.esibuy.esibuy_backend.dto.ProductoCatalogoDTO;
import com.esibuy.esibuy_backend.servicio.ServicioProducto;

/**
 * Catalogo propio del vendedor. Solo para el rol VENDEDOR (regla de /api/vendedor/** en ConfiguracionSeguridad).
 * El vendedor es siempre el de la sesion (el principal es su id): nunca se recibe por parametro, asi que nadie puede
 * pedir el catalogo de otro.
 */
@RestController
@RequestMapping("/api/vendedor/productos")
public class ControladorProducto {

    private final ServicioProducto servicioProducto;

    public ControladorProducto(ServicioProducto servicioProducto) {
        this.servicioProducto = servicioProducto;
    }

    @GetMapping
    @PreAuthorize("hasRole('VENDEDOR')")
    public ResponseEntity<List<ProductoCatalogoDTO>> listarMisProductos(Authentication autenticacion) {
        return ResponseEntity.ok(servicioProducto.listarProductosDelVendedor(autenticacion.getName()));
    }

    @GetMapping("/{id}")
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
}
