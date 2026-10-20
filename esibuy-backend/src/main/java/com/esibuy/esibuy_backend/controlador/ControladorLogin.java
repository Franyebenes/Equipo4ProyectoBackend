package com.esibuy.esibuy_backend.controlador;

import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.esibuy.esibuy_backend.dto.RespuestaLoginDTO;
import com.esibuy.esibuy_backend.dto.RespuestaSegundoFactorRequeridoDTO;
import com.esibuy.esibuy_backend.dto.SolicitudLoginDTO;
import com.esibuy.esibuy_backend.seguridad.EstablecedorSesion;
import com.esibuy.esibuy_backend.seguridad.GestorDesafioSegundoFactor;
import com.esibuy.esibuy_backend.servicio.ContextoPeticion;
import com.esibuy.esibuy_backend.servicio.ResolutorIpCliente;
import com.esibuy.esibuy_backend.servicio.ResultadoAutenticacion;
import com.esibuy.esibuy_backend.servicio.ServicioAutenticacion;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Inicio de sesion (POST /api/auth/login) y token CSRF (GET /api/auth/csrf). Los errores los traduce
 * {@link ManejadorExcepciones}. Tests: ControladorLoginTest.
 */
@RestController
@RequestMapping("/api/auth")
public class ControladorLogin {

    static final String MENSAJE_LOGIN_CORRECTO = "Inicio de sesion correcto";
    private static final String MENSAJE_FALTA_SEGUNDO_FACTOR = "Falta el codigo de la verificacion en dos pasos";

    private final ServicioAutenticacion servicioAutenticacion;
    private final EstablecedorSesion establecedorSesion;
    private final GestorDesafioSegundoFactor gestorDesafio;
    private final ResolutorIpCliente resolutorIp;

    public ControladorLogin(ServicioAutenticacion servicioAutenticacion, EstablecedorSesion establecedorSesion,
                            GestorDesafioSegundoFactor gestorDesafio, ResolutorIpCliente resolutorIp) {
        this.servicioAutenticacion = servicioAutenticacion;
        this.establecedorSesion = establecedorSesion;
        this.gestorDesafio = gestorDesafio;
        this.resolutorIp = resolutorIp;
    }

    /*
     * Si la cuenta tiene el 2FA activo, la contrasena correcta no da sesion: se guarda un desafio pendiente y se responde
     * que falta el codigo (sin ningun dato del usuario). La sesion la crea el segundo paso, ControladorSegundoFactorLogin.
     */
    @PostMapping(path = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> login(@RequestBody SolicitudLoginDTO solicitud, HttpServletRequest peticion) {
        // Unas credenciales en la URL acabarian en logs y en el historial: se rechaza aunque el cuerpo sea valido
        if (peticion.getParameter("email") != null || peticion.getParameter("contrasena") != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        ContextoPeticion contexto = new ContextoPeticion(resolutorIp.resolver(peticion),
                peticion.getHeader(HttpHeaders.USER_AGENT));

        ResultadoAutenticacion resultado = servicioAutenticacion.autenticar(solicitud, contexto);
        if (resultado.requiereSegundoFactor()) {
            gestorDesafio.iniciar(peticion, resultado);
            return ResponseEntity.ok(new RespuestaSegundoFactorRequeridoDTO(true, MENSAJE_FALTA_SEGUNDO_FACTOR));
        }
        establecedorSesion.establecer(peticion, resultado);

        return ResponseEntity.ok(new RespuestaLoginDTO(resultado.id(), resultado.email(), resultado.nombre(),
                resultado.rol(), resultado.avatar(), MENSAJE_LOGIN_CORRECTO));
    }

    //Token CSRF que el formulario de login debe enviar en la cabecera indicada. 
    @GetMapping("/csrf")
    public Map<String, String> tokenCsrf(CsrfToken token) {
        return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
    }
}
