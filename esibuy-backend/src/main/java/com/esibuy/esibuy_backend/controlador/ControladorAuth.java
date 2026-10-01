package com.esibuy.esibuy_backend.controlador;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.esibuy.esibuy_backend.dto.RespuestaRegistroDTO;
import com.esibuy.esibuy_backend.dto.SolicitudRegistroClienteDTO;
import com.esibuy.esibuy_backend.dto.SolicitudRegistroVendedorDTO;

@RestController
@RequestMapping("/api/auth")
public class ControladorAuth {
//temporal hasta que toque implementarlo
    @PostMapping("/login")
    public ResponseEntity<Map<String, String>> login() {
        return ResponseEntity.ok(Map.of("mensaje", "login ok (pendiente de implementar)"));
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, String>> register() {
        return ResponseEntity.ok(Map.of("mensaje", "registro ok (pendiente de implementar)"));
    }

    @PostMapping("/registro/cliente")
    public ResponseEntity<RespuestaRegistroDTO> registrarCliente(@RequestBody SolicitudRegistroClienteDTO solicitud) {
        throw new UnsupportedOperationException("Pendiente de implementar");
    }

    @PostMapping("/registro/vendedor")
    public ResponseEntity<RespuestaRegistroDTO> registrarVendedor(@RequestBody SolicitudRegistroVendedorDTO solicitud) {
        throw new UnsupportedOperationException("Pendiente de implementar");
    }

}
