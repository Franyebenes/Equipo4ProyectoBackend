package com.esibuy.esibuy_backend.controlador;

import java.util.Map;
import java.util.Set;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.esibuy.esibuy_backend.dto.RespuestaRegistroDosFactoresDTO;
import com.esibuy.esibuy_backend.dto.SolicitudRegistro;
import com.esibuy.esibuy_backend.dto.SolicitudRegistroClienteDTO;
import com.esibuy.esibuy_backend.dto.SolicitudRegistroVendedorDTO;
import com.esibuy.esibuy_backend.excepcion.CodigoError;
import com.esibuy.esibuy_backend.excepcion.DatosRegistroInvalidosException;
import com.esibuy.esibuy_backend.seguridad.GestorRegistroDosFactores;
import com.esibuy.esibuy_backend.seguridad.PendienteRegistroDosFactores;
import com.esibuy.esibuy_backend.servicio.AuditoriaSeguridad;
import com.esibuy.esibuy_backend.servicio.ContextoPeticion;
import com.esibuy.esibuy_backend.servicio.ResolutorIpCliente;
import com.esibuy.esibuy_backend.servicio.ServicioRegistro;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Autenticacion y registro. Los errores los traduce {@link ManejadorExcepciones}. Tests: ControladorAuthTest y
 * ControladorAuthDosFactoresTest.
 *
 * <p>El registro exige haber configurado el 2FA: el secreto pendiente (que se pidio en {@code POST /api/auth/registro/2fa}) vive
 * en la sesion y se pasa al servicio, que decide si vale para ese correo. Un codigo incorrecto suma un fallo al pendiente y al
 * agotarlos se descarta; con el registro hecho o con un pendiente no vigente tambien se descarta. Cualquier otro error (formato,
 * dominio, correo repetido...) no lo toca, para que la persona corrija y reintente sin escanear otro QR.
 */
@RestController
@RequestMapping("/api/auth")
public class ControladorAuth {

    private static final String CAMPO_CODIGO = "codigoDosFactores";

    private final ServicioRegistro servicioRegistro;
    private final GestorRegistroDosFactores gestorRegistro;
    private final ResolutorIpCliente resolutorIp;
    private final AuditoriaSeguridad auditoria;

    public ControladorAuth(ServicioRegistro servicioRegistro, GestorRegistroDosFactores gestorRegistro,
                           ResolutorIpCliente resolutorIp, AuditoriaSeguridad auditoria) {
        this.servicioRegistro = servicioRegistro;
        this.gestorRegistro = gestorRegistro;
        this.resolutorIp = resolutorIp;
        this.auditoria = auditoria;
    }

    /**
     * Registro de cualquier tipo de cuenta. El campo {@code tipoCuenta} del JSON decide si es un cliente
     * (CLIENTE o PREMIUM) o un vendedor (VENDEDOR); ver {@link SolicitudRegistro}.
     */
    @PostMapping(path = "/registro", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RespuestaRegistroDosFactoresDTO> registrar(@RequestBody SolicitudRegistro solicitud,
                                                                     HttpServletRequest peticion) {
        ContextoPeticion contexto = new ContextoPeticion(resolutorIp.resolver(peticion),
                peticion.getHeader(HttpHeaders.USER_AGENT));
        PendienteRegistroDosFactores pendiente = gestorRegistro.vigente(peticion).orElse(null);

        RespuestaRegistroDosFactoresDTO respuesta;
        try {
            respuesta = switch (solicitud) {
                case SolicitudRegistroClienteDTO cliente -> servicioRegistro.registrarCliente(cliente, pendiente, contexto);
                case SolicitudRegistroVendedorDTO vendedor -> servicioRegistro.registrarVendedor(vendedor, pendiente, contexto);
            };
        } catch (DatosRegistroInvalidosException e) {
            throw tratarErrorDelCodigo(e, peticion, contexto);
        }
        gestorRegistro.descartar(peticion);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(respuesta);
    }

    private DatosRegistroInvalidosException tratarErrorDelCodigo(DatosRegistroInvalidosException e, HttpServletRequest peticion,
                                                                 ContextoPeticion contexto) {
        Set<CodigoError> deCodigo = e.getErrores().get(CAMPO_CODIGO);
        if (deCodigo == null) {
            return e;
        }
        if (deCodigo.contains(CodigoError.ACTIVACION_NO_VIGENTE)) {
            gestorRegistro.descartar(peticion);
        } else if (deCodigo.contains(CodigoError.CODIGO_INCORRECTO) && gestorRegistro.registrarFallo(peticion)) {
            // Ultimo intento: ya no hay nada que reintentar con ese QR
            auditoria.registrarRegistroDosFactoresAgotado(contexto);
            return new DatosRegistroInvalidosException(Map.of(CAMPO_CODIGO, Set.of(CodigoError.ACTIVACION_NO_VIGENTE)));
        }
        return e;
    }
}
