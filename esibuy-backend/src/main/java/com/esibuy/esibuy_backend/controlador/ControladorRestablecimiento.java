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

import com.esibuy.esibuy_backend.dto.RespuestaRestablecimientoDTO;
import com.esibuy.esibuy_backend.dto.SolicitudRestablecimientoDTO;
import com.esibuy.esibuy_backend.servicio.ContextoPeticion;
import com.esibuy.esibuy_backend.servicio.ResolutorIpCliente;
import com.esibuy.esibuy_backend.servicio.ServicioRestablecimiento;

import jakarta.servlet.http.HttpServletRequest;

/*
 * Restablecimiento de la contrasena con el token del correo (POST /api/auth/recuperacion/restablecer). Publico, con
 * CSRF. No inicia sesion: el usuario entra despues con su contrasena nueva. Los errores los traduce
 * {@link ManejadorExcepciones}. Tests: ControladorRestablecimientoTest.
 */
@RestController
@RequestMapping("/api/auth")
public class ControladorRestablecimiento {

    private final ServicioRestablecimiento servicio;
    private final ResolutorIpCliente resolutorIp;

    public ControladorRestablecimiento(ServicioRestablecimiento servicio, ResolutorIpCliente resolutorIp) {
        this.servicio = servicio;
        this.resolutorIp = resolutorIp;
    }

    @PostMapping(path = "/recuperacion/restablecer", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RespuestaRestablecimientoDTO> restablecer(@RequestBody SolicitudRestablecimientoDTO solicitud,
            HttpServletRequest peticion) {
        // Un token o una contrasena en la URL acabarian en logs y en el historial: se rechaza aunque el cuerpo sea valido
        if (peticion.getParameter("token") != null || peticion.getParameter("contrasena") != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        ContextoPeticion contexto = new ContextoPeticion(resolutorIp.resolver(peticion),
                peticion.getHeader(HttpHeaders.USER_AGENT));

        servicio.restablecer(solicitud, contexto);
        return ResponseEntity.ok(RespuestaRestablecimientoDTO.correcta());
    }
}
