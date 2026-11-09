package com.esibuy.esibuy_backend.controlador;

import java.util.Set;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.esibuy.esibuy_backend.dto.RespuestaLoginDTO;
import com.esibuy.esibuy_backend.dto.SolicitudSegundoFactorLoginDTO;
import com.esibuy.esibuy_backend.excepcion.CodigoError;
import com.esibuy.esibuy_backend.excepcion.DatosDosFactoresInvalidosException;
import com.esibuy.esibuy_backend.excepcion.SegundoFactorNoVigenteException;
import com.esibuy.esibuy_backend.seguridad.DesafioSegundoFactor;
import com.esibuy.esibuy_backend.seguridad.EstablecedorSesion;
import com.esibuy.esibuy_backend.seguridad.GestorDesafioSegundoFactor;
import com.esibuy.esibuy_backend.servicio.AuditoriaSeguridad;
import com.esibuy.esibuy_backend.servicio.ContextoPeticion;
import com.esibuy.esibuy_backend.servicio.ResolutorIpCliente;
import com.esibuy.esibuy_backend.servicio.ResultadoAutenticacion;
import com.esibuy.esibuy_backend.servicio.ServicioDosFactores;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Segundo paso del login (POST /api/auth/2fa/verificar): el codigo de la aplicacion autenticadora, o uno de recuperacion.
 * Publico pero con token CSRF, como el login: solo vale con un desafio vigente en la sesion, que guarda el primer paso. Con un
 * codigo correcto crea la sesion definitiva (EstablecedorSesion invalida la anterior: sin fijacion de sesion) y responde igual
 * que un login normal. Los errores los traduce {@link ManejadorExcepciones}. Tests: ControladorLoginDosFactoresTest.
 *
 * <p>Sin desafio vigente (no lo hay, caduco, o la cuenta cambio entre los dos pasos): 401 siempre con el mismo mensaje. Cada
 * codigo incorrecto cuenta contra el desafio, que se descarta al llegar al maximo; un codigo vacio o un 429 no cuentan.
 */
@RestController
@RequestMapping("/api/auth/2fa")
public class ControladorSegundoFactorLogin {

    private final ServicioDosFactores servicio;
    private final GestorDesafioSegundoFactor gestorDesafio;
    private final EstablecedorSesion establecedorSesion;
    private final ResolutorIpCliente resolutorIp;
    private final AuditoriaSeguridad auditoria;

    public ControladorSegundoFactorLogin(ServicioDosFactores servicio, GestorDesafioSegundoFactor gestorDesafio,
                                         EstablecedorSesion establecedorSesion, ResolutorIpCliente resolutorIp,
                                         AuditoriaSeguridad auditoria) {
        this.servicio = servicio;
        this.gestorDesafio = gestorDesafio;
        this.establecedorSesion = establecedorSesion;
        this.resolutorIp = resolutorIp;
        this.auditoria = auditoria;
    }

    @PostMapping(path = "/verificar", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RespuestaLoginDTO> verificar(@RequestBody SolicitudSegundoFactorLoginDTO solicitud,
                                                       HttpServletRequest peticion) {
        // Un codigo en la URL acabaria en logs y en el historial: se rechaza aunque el cuerpo sea valido
        if (peticion.getParameter("codigo") != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        ContextoPeticion contexto = new ContextoPeticion(resolutorIp.resolver(peticion),
                peticion.getHeader(HttpHeaders.USER_AGENT));
        DesafioSegundoFactor desafio = gestorDesafio.vigente(peticion).orElseThrow(() -> {
            auditoria.registrarLoginSegundoFactorNoVigente(contexto);
            return new SegundoFactorNoVigenteException();
        });
        ResultadoAutenticacion usuario = desafio.usuario();

        try {
            servicio.verificarParaLogin(usuario.id(), solicitud.codigo(), contexto);
        } catch (SegundoFactorNoVigenteException e) {
            gestorDesafio.descartar(peticion);
            throw e;
        } catch (DatosDosFactoresInvalidosException e) {
            if (esCodigoIncorrecto(e) && gestorDesafio.registrarFallo(peticion)) {
                auditoria.registrarLoginSegundoFactorAgotado(contexto, usuario.email(), usuario.id());
            }
            throw e;
        }

        gestorDesafio.descartar(peticion);
        // La sesion definitiva ya no lleva el indicador de segundo factor pendiente
        ResultadoAutenticacion autenticado = new ResultadoAutenticacion(usuario.id(), usuario.email(), usuario.nombre(),
                usuario.rol(), usuario.avatar());
        establecedorSesion.establecer(peticion, autenticado);
        return ResponseEntity.ok(new RespuestaLoginDTO(autenticado.id(), autenticado.email(), autenticado.nombre(),
                autenticado.rol(), autenticado.avatar(), ControladorLogin.MENSAJE_LOGIN_CORRECTO));
    }

    // Solo un codigo que no vale cuenta contra el desafio: un campo vacio no
    private static boolean esCodigoIncorrecto(DatosDosFactoresInvalidosException e) {
        return e.getErrores().getOrDefault("codigo", Set.of()).contains(CodigoError.CODIGO_INCORRECTO);
    }
}
