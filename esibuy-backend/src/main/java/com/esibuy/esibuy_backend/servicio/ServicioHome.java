package com.esibuy.esibuy_backend.servicio;

import java.util.List;

import org.springframework.stereotype.Service;

import com.esibuy.esibuy_backend.dto.ContactoDTO;
import com.esibuy.esibuy_backend.dto.HomeInfoDTO;

@Service
public class ServicioHome {

    public HomeInfoDTO getHomeInfo() {
        return new HomeInfoDTO(
                "ESIBuy",
                "Plataforma de comercio electrónico segura, escalable y accesible.",
                "1.0.0",
                List.of(
                        "Búsqueda y filtrado de productos",
                        "Gestión de carrito y pedidos",
                        "Valoraciones y favoritos",
                        "Seguimiento de envíos"
                ),
                new ContactoDTO("soporte@esibuy.com", "+34 900 000 000")
        );
    }
}