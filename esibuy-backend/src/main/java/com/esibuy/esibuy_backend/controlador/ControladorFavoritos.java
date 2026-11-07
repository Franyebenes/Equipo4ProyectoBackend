package com.esibuy.esibuy_backend.controlador;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.esibuy.esibuy_backend.dto.ProductoClienteDTO;
import com.esibuy.esibuy_backend.modelo.Rol;
import com.esibuy.esibuy_backend.servicio.ServicioFavoritos;

/**
 * HU-31 Favoritos del cliente (estandar y premium). El cliente es siempre el de la sesion (el principal es su id):
 * nunca se recibe por parametro, asi que nadie puede ver ni cambiar los favoritos de otro. POST y DELETE exigen token
 * CSRF.
 */
@RestController
@RequestMapping("/api/favoritos")
public class ControladorFavoritos {

    private static final String AUTORIDAD_PREMIUM = "ROLE_" + Rol.PREMIUM.name();
    private static final String RUTA_PRODUCTO = "/{idProducto}";

    private final ServicioFavoritos servicioFavoritos;

    public ControladorFavoritos(ServicioFavoritos servicioFavoritos) {
        this.servicioFavoritos = servicioFavoritos;
    }

    // HU-31.1 Lista de favoritos, con la misma informacion que el catalogo
    @GetMapping
    @PreAuthorize("hasRole('CLIENTE')")
    public ResponseEntity<List<ProductoClienteDTO>> listar(Authentication autenticacion) {
        return ResponseEntity.ok(servicioFavoritos.listar(autenticacion.getName(), esPremium(autenticacion)));
    }

    // Ids de los favoritos, para pintar el corazon relleno en el catalogo y en la ficha
    @GetMapping("/ids")
    @PreAuthorize("hasRole('CLIENTE')")
    public ResponseEntity<List<String>> ids(Authentication autenticacion) {
        return ResponseEntity.ok(servicioFavoritos.idsFavoritos(autenticacion.getName()));
    }

    // HU-31.2 Anadir a favoritos: 201; si ya estaba, 409; si el producto no existe o esta inactivo, 404
    @PostMapping(RUTA_PRODUCTO)
    @PreAuthorize("hasRole('CLIENTE')")
    public ResponseEntity<Void> anadir(@PathVariable String idProducto, Authentication autenticacion) {
        servicioFavoritos.anadir(autenticacion.getName(), idProducto);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    // HU-31.3 Quitar de favoritos: 204; si no estaba en sus favoritos, 404
    @DeleteMapping(RUTA_PRODUCTO)
    @PreAuthorize("hasRole('CLIENTE')")
    public ResponseEntity<Void> eliminar(@PathVariable String idProducto, Authentication autenticacion) {
        servicioFavoritos.eliminar(autenticacion.getName(), idProducto);
        return ResponseEntity.noContent().build();
    }

    private static boolean esPremium(Authentication autenticacion) {
        return autenticacion.getAuthorities().stream().anyMatch(a -> AUTORIDAD_PREMIUM.equals(a.getAuthority()));
    }
}
