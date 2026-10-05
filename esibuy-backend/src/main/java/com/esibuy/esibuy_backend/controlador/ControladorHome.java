package com.esibuy.esibuy_backend.controlador;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.esibuy.esibuy_backend.dto.HomeInfoDTO;
import com.esibuy.esibuy_backend.servicio.ServicioHome;

/** Informacion publica de la portada. */
@RestController
@RequestMapping("/api/public")
public class ControladorHome {

    private final ServicioHome servicioHome;

    public ControladorHome(ServicioHome servicioHome) {
        this.servicioHome = servicioHome;
    }

    @GetMapping("/home")
    public HomeInfoDTO getHomeInfo() {
        return servicioHome.getHomeInfo();
    }
}
