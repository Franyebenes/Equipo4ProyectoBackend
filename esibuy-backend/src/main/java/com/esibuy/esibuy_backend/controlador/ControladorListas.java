package com.esibuy.esibuy_backend.controlador;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.esibuy.esibuy_backend.dto.ListaDTO;
import com.esibuy.esibuy_backend.dto.ListaDetalleDTO;
import com.esibuy.esibuy_backend.dto.SolicitudListaDTO;
import com.esibuy.esibuy_backend.modelo.Rol;
import com.esibuy.esibuy_backend.servicio.ServicioListas;

import jakarta.validation.Valid;

/** Listas de productos del cliente de la sesion, con Favoritos por defecto. Solo clientes y premium. */
@RestController
@RequestMapping("/api/cliente/listas")
@PreAuthorize("hasAnyRole('CLIENTE', 'PREMIUM')")
public class ControladorListas {

    private static final String AUTORIDAD_PREMIUM = "ROLE_" + Rol.PREMIUM.name();
    private static final String RUTA_ID = "/{id}";
    private static final String RUTA_PRODUCTO = RUTA_ID + "/productos/{idProducto}";

    private final ServicioListas servicio;

    public ControladorListas(ServicioListas servicio) {
        this.servicio = servicio;
    }

    @GetMapping
    public List<ListaDTO> listar(Authentication autenticacion) {
        return servicio.listar(autenticacion.getName());
    }

    @GetMapping(RUTA_ID)
    public ListaDetalleDTO obtener(@PathVariable String id, Authentication autenticacion) {
        return servicio.obtener(autenticacion.getName(), id, esPremium(autenticacion));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ListaDTO> crear(@Valid @RequestBody SolicitudListaDTO solicitud,
                                          Authentication autenticacion) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(servicio.crear(autenticacion.getName(), solicitud.nombre()));
    }

    @PutMapping(value = RUTA_ID, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ListaDTO renombrar(@PathVariable String id, @Valid @RequestBody SolicitudListaDTO solicitud,
                              Authentication autenticacion) {
        return servicio.renombrar(autenticacion.getName(), id, solicitud.nombre());
    }

    @DeleteMapping(RUTA_ID)
    public ResponseEntity<Void> eliminar(@PathVariable String id, Authentication autenticacion) {
        servicio.eliminar(autenticacion.getName(), id);
        return ResponseEntity.noContent().build();
    }

    /** PUT porque es idempotente: anadir dos veces el mismo producto deja la lista igual. */
    @PutMapping(RUTA_PRODUCTO)
    public ListaDTO anadirProducto(@PathVariable String id, @PathVariable String idProducto,
                                   Authentication autenticacion) {
        return servicio.anadirProducto(autenticacion.getName(), id, idProducto, esPremium(autenticacion));
    }

    @DeleteMapping(RUTA_PRODUCTO)
    public ListaDTO quitarProducto(@PathVariable String id, @PathVariable String idProducto,
                                   Authentication autenticacion) {
        return servicio.quitarProducto(autenticacion.getName(), id, idProducto);
    }

    private static boolean esPremium(Authentication autenticacion) {
        return autenticacion.getAuthorities().stream().anyMatch(a -> AUTORIDAD_PREMIUM.equals(a.getAuthority()));
    }
}