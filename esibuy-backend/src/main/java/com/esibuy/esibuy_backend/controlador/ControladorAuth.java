package com.esibuy.esibuy_backend.controlador;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.esibuy.esibuy_backend.dto.RespuestaRegistroDTO;
import com.esibuy.esibuy_backend.dto.SolicitudRegistro;
import com.esibuy.esibuy_backend.dto.SolicitudRegistroClienteDTO;
import com.esibuy.esibuy_backend.dto.SolicitudRegistroVendedorDTO;
import com.esibuy.esibuy_backend.servicio.ServicioRegistro;

/**
 * Autenticacion y registro. Los errores los traduce {@link ManejadorExcepciones}. Tests: ControladorAuthTest.
 */
@RestController
@RequestMapping("/api/auth")
public class ControladorAuth {

    private final ServicioRegistro servicioRegistro;

    public ControladorAuth(ServicioRegistro servicioRegistro) {
        this.servicioRegistro = servicioRegistro;
    }

    /**
     * Registro de cualquier tipo de cuenta. El campo {@code tipoCuenta} del JSON decide si es un cliente
     * (CLIENTE o PREMIUM) o un vendedor (VENDEDOR); ver {@link SolicitudRegistro}.
     */
    @PostMapping(path = "/registro", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RespuestaRegistroDTO> registrar(@RequestBody SolicitudRegistro solicitud) {
        RespuestaRegistroDTO respuesta = switch (solicitud) {
            case SolicitudRegistroClienteDTO cliente -> servicioRegistro.registrarCliente(cliente);
            case SolicitudRegistroVendedorDTO vendedor -> servicioRegistro.registrarVendedor(vendedor);
        };
        return ResponseEntity.status(HttpStatus.CREATED).body(respuesta);
    }
}
