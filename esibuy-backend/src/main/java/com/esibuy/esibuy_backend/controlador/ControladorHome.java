package com.esibuy.esibuy_backend.controlador;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.esibuy.esibuy_backend.dto.HomeInfoDTO;
import com.esibuy.esibuy_backend.servicio.ServicioHome;

@RestController
@RequestMapping("/api/public")
public class ControladorHome {

    private final ServicioHome servicio_home;

    public ControladorHome(ServicioHome homeService) {
        this.servicio_home = homeService;
    }

    @GetMapping("/home")
    public ResponseEntity<HomeInfoDTO> getHomeInfo() {
        return ResponseEntity.ok(servicio_home.getHomeInfo());
    }
}