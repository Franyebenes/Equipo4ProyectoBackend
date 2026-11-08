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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.esibuy.esibuy_backend.dto.MetodoPagoAltaDTO;
import com.esibuy.esibuy_backend.dto.MetodoPagoDTO;
import com.esibuy.esibuy_backend.servicio.ServicioMetodoPago;

/**
 * Metodos de pago del cliente (HU-24.x). Solo clientes y premium; siempre los del cliente de la sesion.
 */
@RestController
@RequestMapping("/api/metodos-pago")
public class ControladorMetodoPago {

    private final ServicioMetodoPago servicio;

    public ControladorMetodoPago(ServicioMetodoPago servicio) {
        this.servicio = servicio;
    }

    // HU-24.1
    @GetMapping
    @PreAuthorize("hasAnyRole('CLIENTE', 'PREMIUM')")
    public ResponseEntity<List<MetodoPagoDTO>> listar(Authentication autenticacion) {
        return ResponseEntity.ok(servicio.listar(autenticacion.getName()));
    }

    // HU-24.2
    @PostMapping
    @PreAuthorize("hasAnyRole('CLIENTE', 'PREMIUM')")
    public ResponseEntity<MetodoPagoDTO> anadir(@RequestBody MetodoPagoAltaDTO datos, Authentication autenticacion) {
        return ResponseEntity.status(HttpStatus.CREATED).body(servicio.anadir(autenticacion.getName(), datos));
    }

    // HU-24.3
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('CLIENTE', 'PREMIUM')")
    public ResponseEntity<Void> eliminar(@PathVariable String id, Authentication autenticacion) {
        servicio.eliminar(autenticacion.getName(), id);
        return ResponseEntity.noContent().build();
    }
}