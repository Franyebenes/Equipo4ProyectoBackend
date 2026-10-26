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

import com.esibuy.esibuy_backend.dto.RespuestaActivacionDosFactoresDTO;
import com.esibuy.esibuy_backend.dto.SolicitudRegistroDosFactoresDTO;
import com.esibuy.esibuy_backend.seguridad.GestorRegistroDosFactores;
import com.esibuy.esibuy_backend.servicio.ContextoPeticion;
import com.esibuy.esibuy_backend.servicio.PreparacionRegistroDosFactores;
import com.esibuy.esibuy_backend.servicio.ResolutorIpCliente;
import com.esibuy.esibuy_backend.servicio.ServicioRegistroDosFactores;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Primer paso del 2FA en el registro (POST /api/auth/registro/2fa): genera el secreto para el correo indicado y devuelve la URI
 * del QR y el secreto en Base32. Es publico (se usa antes de tener cuenta) pero exige token CSRF, porque guarda estado en la
 * sesion: el secreto pendiente, cifrado y ligado al correo (GestorRegistroDosFactores), que el registro final recuperara. Pedirlo
 * de nuevo sustituye al pendiente; si la peticion falla, el pendiente que hubiera no se toca. Los errores los traduce
 * {@link ManejadorExcepciones}. Tests: ControladorRegistroDosFactoresTest.
 */
@RestController
@RequestMapping("/api/auth")
public class ControladorRegistroDosFactores {

    private final ServicioRegistroDosFactores servicio;
    private final GestorRegistroDosFactores gestor;
    private final ResolutorIpCliente resolutorIp;

    public ControladorRegistroDosFactores(ServicioRegistroDosFactores servicio, GestorRegistroDosFactores gestor,
                                          ResolutorIpCliente resolutorIp) {
        this.servicio = servicio;
        this.gestor = gestor;
        this.resolutorIp = resolutorIp;
    }

    @PostMapping(path = "/registro/2fa", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RespuestaActivacionDosFactoresDTO> preparar(@RequestBody SolicitudRegistroDosFactoresDTO solicitud,
                                                                      HttpServletRequest peticion) {
        // Un correo en la URL acabaria en logs y en el historial: se rechaza aunque el cuerpo sea valido
        if (peticion.getParameter("email") != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        ContextoPeticion contexto = new ContextoPeticion(resolutorIp.resolver(peticion),
                peticion.getHeader(HttpHeaders.USER_AGENT));

        PreparacionRegistroDosFactores preparacion = servicio.preparar(solicitud.email(), contexto);
        gestor.iniciar(peticion, preparacion.email(), preparacion.secretoCifrado());
        return ResponseEntity.ok(new RespuestaActivacionDosFactoresDTO(preparacion.uri(), preparacion.secreto()));
    }
}
