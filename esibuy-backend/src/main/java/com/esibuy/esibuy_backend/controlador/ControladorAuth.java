package com.esibuy.esibuy_backend.controlador;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.esibuy.esibuy_backend.dto.RespuestaRegistroDTO;
import com.esibuy.esibuy_backend.dto.SolicitudRegistroClienteDTO;
import com.esibuy.esibuy_backend.dto.SolicitudRegistroVendedorDTO;
import com.esibuy.esibuy_backend.servicio.ServicioRegistro;

// Autenticacion y registro. Los errores los traduce {@link ManejadorExcepciones}. Tests: ControladorAuthTest.

@RestController
@RequestMapping("/api/auth")
public class ControladorAuth {

    private final ServicioRegistro servicioRegistro;

    public ControladorAuth(ServicioRegistro servicioRegistro) {
        this.servicioRegistro = servicioRegistro;
    }

    //temporal hasta que toque implementarlo
    @PostMapping("/login")
    public ResponseEntity<Map<String, String>> login() {
        return ResponseEntity.ok(Map.of("mensaje", "login ok (pendiente de implementar)"));
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, String>> register() {
        return ResponseEntity.ok(Map.of("mensaje", "registro ok (pendiente de implementar)"));
    }

    @PostMapping(path = "/registro/cliente", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RespuestaRegistroDTO> registrarCliente(@RequestBody SolicitudRegistroClienteDTO solicitud) {
        return ResponseEntity.status(HttpStatus.CREATED).body(servicioRegistro.registrarCliente(solicitud));
    }

    @PostMapping(path = "/registro/vendedor", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RespuestaRegistroDTO> registrarVendedor(@RequestBody SolicitudRegistroVendedorDTO solicitud) {
        return ResponseEntity.status(HttpStatus.CREATED).body(servicioRegistro.registrarVendedor(solicitud));
    }
}
