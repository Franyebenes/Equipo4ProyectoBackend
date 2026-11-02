package com.esibuy.esibuy_backend.controlador;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.esibuy.esibuy_backend.dto.DireccionDTO;
import com.esibuy.esibuy_backend.dto.SolicitudDireccionDTO;
import com.esibuy.esibuy_backend.servicio.ServicioDireccion;

import jakarta.validation.Valid;

/** Libreta de direcciones . Solo clientes y premium, siempre sobre las direcciones del usuario de la sesion. */
@RestController
@RequestMapping("/api/cliente/direcciones")
@PreAuthorize("hasAnyRole('CLIENTE', 'PREMIUM')")
public class ControladorDireccion {

    private static final String RUTA_ID = "/{id}";

    private final ServicioDireccion servicio;

    public ControladorDireccion(ServicioDireccion servicio) {
        this.servicio = servicio;
    }

    @GetMapping
    public List<DireccionDTO> listar(Authentication autenticacion) {
        return servicio.listar(autenticacion.getName());
    }

    @GetMapping(RUTA_ID)
    public DireccionDTO obtener(@PathVariable String id, Authentication autenticacion) {
        return servicio.obtener(autenticacion.getName(), id);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DireccionDTO> crear(@Valid @RequestBody SolicitudDireccionDTO solicitud,
                                              Authentication autenticacion) {
        return ResponseEntity.status(HttpStatus.CREATED).body(servicio.crear(autenticacion.getName(), solicitud));
    }

    @PutMapping(value = RUTA_ID, consumes = MediaType.APPLICATION_JSON_VALUE)
    public DireccionDTO modificar(@PathVariable String id, @Valid @RequestBody SolicitudDireccionDTO solicitud,
                                  Authentication autenticacion) {
        return servicio.modificar(autenticacion.getName(), id, solicitud);
    }

    @PatchMapping(RUTA_ID + "/predeterminada")
    public DireccionDTO marcarPredeterminada(@PathVariable String id, Authentication autenticacion) {
        return servicio.marcarPredeterminada(autenticacion.getName(), id);
    }

    @DeleteMapping(RUTA_ID)
    public ResponseEntity<Void> eliminar(@PathVariable String id, Authentication autenticacion) {
        servicio.eliminar(autenticacion.getName(), id);
        return ResponseEntity.noContent().build();
    }
}