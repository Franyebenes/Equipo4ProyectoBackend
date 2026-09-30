package com.esibuy.esibuy_backend.controlador;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

}
