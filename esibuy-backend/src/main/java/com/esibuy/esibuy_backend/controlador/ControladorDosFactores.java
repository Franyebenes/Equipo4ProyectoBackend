package com.esibuy.esibuy_backend.controlador;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.esibuy.esibuy_backend.dto.EstadoDosFactoresDTO;
import com.esibuy.esibuy_backend.dto.RespuestaActivacionDosFactoresDTO;
import com.esibuy.esibuy_backend.dto.RespuestaCodigosRecuperacionDTO;
import com.esibuy.esibuy_backend.dto.SolicitudActivacionDosFactoresDTO;
import com.esibuy.esibuy_backend.dto.SolicitudConfirmacionDosFactoresDTO;
import com.esibuy.esibuy_backend.dto.SolicitudVerificacionDosFactoresDTO;
import com.esibuy.esibuy_backend.seguridad.EstablecedorSesion;
import com.esibuy.esibuy_backend.servicio.ContextoPeticion;
import com.esibuy.esibuy_backend.servicio.ResolutorIpCliente;
import com.esibuy.esibuy_backend.servicio.ResultadoAutenticacion;
import com.esibuy.esibuy_backend.servicio.ServicioDosFactores;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/*
 * Segundo factor (TOTP) de la cuenta de la sesion: consultar el estado, activarlo y regenerar los codigos de
 * recuperacion. Cuelga de /api/2fa y no de /api/auth: todo POST de /api/auth es publico y estas operaciones exigen sesion
 * (401 sin ella) y token CSRF. La cuenta es siempre la de la sesion (el principal es su id): nunca se recibe por parametro,
 * asi que nadie puede tocar el 2FA de otro. Los errores los traduce {@link ManejadorExcepciones}. Tests:
 * ControladorDosFactoresTest.
 *
 * <p>Una vez activado, el 2FA queda fijo: no hay endpoint para desactivarlo ni restablecerlo (decision del PO para el
 * prototipo).
 *
 * <p>Tras activar o regenerar con exito, la sesion se renueva: se invalida la actual y se crea otra con el mismo
 * usuario (nuevo identificador). El servicio ya ha marcado las credenciales como cambiadas, asi que las demas sesiones de la
 * cuenta dejan de valer y esta, que empieza justo despues, sigue valiendo.
 */
@RestController
@RequestMapping("/api/2fa")
public class ControladorDosFactores {

    private final ServicioDosFactores servicio;
    private final EstablecedorSesion establecedorSesion;
    private final ResolutorIpCliente resolutorIp;

    public ControladorDosFactores(ServicioDosFactores servicio, EstablecedorSesion establecedorSesion,
                                  ResolutorIpCliente resolutorIp) {
        this.servicio = servicio;
        this.establecedorSesion = establecedorSesion;
        this.resolutorIp = resolutorIp;
    }

    @GetMapping
    public ResponseEntity<EstadoDosFactoresDTO> estado(Authentication autenticacion) {
        return ResponseEntity.ok(servicio.estado(autenticacion.getName()));
    }

    // Pide el QR: devuelve la URI y el secreto. Todavia no cambia nada en la cuenta, asi que no renueva la sesion.
    @PostMapping(path = "/activacion", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RespuestaActivacionDosFactoresDTO> iniciarActivacion(
            @RequestBody SolicitudActivacionDosFactoresDTO solicitud, Authentication autenticacion,
            HttpServletRequest peticion) {
        rechazarDatosEnLaUrl(peticion);
        return ResponseEntity.ok(servicio.iniciarActivacion(autenticacion.getName(), solicitud.contrasena(),
                contextoDe(peticion)));
    }

    @PostMapping(path = "/activacion/confirmar", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RespuestaCodigosRecuperacionDTO> confirmarActivacion(
            @RequestBody SolicitudConfirmacionDosFactoresDTO solicitud, Authentication autenticacion,
            HttpServletRequest peticion) {
        rechazarDatosEnLaUrl(peticion);
        RespuestaCodigosRecuperacionDTO respuesta = servicio.confirmarActivacion(autenticacion.getName(),
                solicitud.codigo(), contextoDe(peticion));
        renovarSesion(peticion);
        return ResponseEntity.ok(respuesta);
    }

    @PostMapping(path = "/codigos-recuperacion", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RespuestaCodigosRecuperacionDTO> regenerarCodigos(
            @RequestBody SolicitudVerificacionDosFactoresDTO solicitud, Authentication autenticacion,
            HttpServletRequest peticion) {
        rechazarDatosEnLaUrl(peticion);
        RespuestaCodigosRecuperacionDTO respuesta = servicio.regenerarCodigos(autenticacion.getName(),
                solicitud.contrasena(), solicitud.codigo(), contextoDe(peticion));
        renovarSesion(peticion);
        return ResponseEntity.ok(respuesta);
    }

    // Una contrasena o un codigo en la URL acabarian en logs y en el historial: se rechaza aunque el cuerpo sea valido
    private static void rechazarDatosEnLaUrl(HttpServletRequest peticion) {
        if (peticion.getParameter("contrasena") != null || peticion.getParameter("codigo") != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
    }

    private ContextoPeticion contextoDe(HttpServletRequest peticion) {
        return new ContextoPeticion(resolutorIp.resolver(peticion), peticion.getHeader(HttpHeaders.USER_AGENT));
    }

    // Invalida la sesion actual y crea otra con el mismo usuario: nuevo identificador tras un cambio de seguridad.
    private void renovarSesion(HttpServletRequest peticion) {
        HttpSession sesion = peticion.getSession(false);
        if (sesion != null && sesion.getAttribute(EstablecedorSesion.ATRIBUTO_USUARIO) instanceof ResultadoAutenticacion usuario) {
            establecedorSesion.establecer(peticion, usuario);
        }
    }
}
