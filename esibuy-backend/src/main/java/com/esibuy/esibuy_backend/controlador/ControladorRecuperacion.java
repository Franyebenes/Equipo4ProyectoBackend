package com.esibuy.esibuy_backend.controlador;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.esibuy.esibuy_backend.dto.RespuestaRecuperacionDTO;
import com.esibuy.esibuy_backend.dto.SolicitudRecuperacionDTO;
import com.esibuy.esibuy_backend.servicio.ContextoPeticion;
import com.esibuy.esibuy_backend.servicio.ResolutorIpCliente;
import com.esibuy.esibuy_backend.servicio.ServicioRecuperacion;

import jakarta.servlet.http.HttpServletRequest;

/*
 * Solicitud de recuperacion de contrasena (POST /api/auth/recuperacion). Publico, con CSRF. Responde siempre lo
 * mismo: el controlador no sabe, ni dice, si la cuenta existe. Los errores los traduce {@link ManejadorExcepciones}.
 * Tests: ControladorRecuperacionTest.
 */
@RestController
@RequestMapping("/api/auth")
public class ControladorRecuperacion {

    private final ServicioRecuperacion servicio;
    private final ResolutorIpCliente resolutorIp;

    public ControladorRecuperacion(ServicioRecuperacion servicio, ResolutorIpCliente resolutorIp) {
        this.servicio = servicio;
        this.resolutorIp = resolutorIp;
    }

    @PostMapping(path = "/recuperacion", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RespuestaRecuperacionDTO> solicitar(@RequestBody SolicitudRecuperacionDTO solicitud,
            HttpServletRequest peticion) {
        // Un correo en la URL acabaria en logs y en el historial: se rechaza aunque el cuerpo sea valido
        if (peticion.getParameter("email") != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        ContextoPeticion contexto = new ContextoPeticion(resolutorIp.resolver(peticion),
                peticion.getHeader(HttpHeaders.USER_AGENT));

        servicio.solicitar(solicitud, contexto);
        return ResponseEntity.ok(RespuestaRecuperacionDTO.generica());
    }
}
