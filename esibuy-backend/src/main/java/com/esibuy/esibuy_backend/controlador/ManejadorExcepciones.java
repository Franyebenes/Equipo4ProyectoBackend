package com.esibuy.esibuy_backend.controlador;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.esibuy.esibuy_backend.configuracion.FiltroCorrelacionId;
import com.esibuy.esibuy_backend.excepcion.CodigoError;
import com.esibuy.esibuy_backend.excepcion.CuerpoDemasiadoGrandeException;
import com.esibuy.esibuy_backend.excepcion.DatosRegistroInvalidosException;
import com.esibuy.esibuy_backend.excepcion.RegistroNoCompletadoException;
import com.esibuy.esibuy_backend.excepcion.ServicioNoDisponibleException;

import tools.jackson.databind.DatabindException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

// Traduce las excepciones a respuestas HTTP sin filtrar detalles internos /*
/* Formatos:
 *   400 de validacion: {"errores": {campo: [codigos]}}</li>
 *   Resto de errores: {"mensaje": ...}, y en 500 y 503 tambien "correlationId". El detalle completo solo va al log, con ese mismo correlationId.</li>
 * Las excepciones estandar de Spring MVC (415, 405, 404...) las resuelve ResponseEntityExceptionHandler, con el
 * cuerpo unificado en {@link #handleExceptionInternal}.
 */
@RestControllerAdvice
public class ManejadorExcepciones extends ResponseEntityExceptionHandler {

    static final String MENSAJE_PETICION_INVALIDA = "La peticion no es valida";
    static final String MENSAJE_CUERPO_DEMASIADO_GRANDE = "La peticion es demasiado grande";
    static final String MENSAJE_SERVICIO_NO_DISPONIBLE =
            "El servicio no esta disponible en este momento. Vuelve a intentarlo mas tarde";
    static final String MENSAJE_ERROR_INESPERADO =
            "Se ha producido un error inesperado. Si persiste, indica este codigo al soporte";

    private static final String CLAVE_ERRORES = "errores";
    private static final String CLAVE_MENSAJE = "mensaje";

    private static final Logger log = LoggerFactory.getLogger(ManejadorExcepciones.class);

    @ExceptionHandler(DatosRegistroInvalidosException.class)
    public ResponseEntity<Map<String, Object>> datosInvalidos(DatosRegistroInvalidosException ex) {
        return ResponseEntity.badRequest().body(Map.of(CLAVE_ERRORES, ex.getErrores()));
    }

    @ExceptionHandler(RegistroNoCompletadoException.class)
    public ResponseEntity<Map<String, Object>> registroNoCompletado() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of(CLAVE_MENSAJE, RegistroNoCompletadoException.MENSAJE_GENERICO));
    }

    @ExceptionHandler(CuerpoDemasiadoGrandeException.class)
    public ResponseEntity<Map<String, Object>> cuerpoDemasiadoGrande() {
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                .body(Map.of(CLAVE_MENSAJE, MENSAJE_CUERPO_DEMASIADO_GRANDE));
    }

    @ExceptionHandler(ServicioNoDisponibleException.class)
    public ResponseEntity<Map<String, Object>> servicioNoDisponible(ServicioNoDisponibleException ex) {
        log.warn("Servicio externo no disponible", ex);
        return conCorrelationId(HttpStatus.SERVICE_UNAVAILABLE, MENSAJE_SERVICIO_NO_DISPONIBLE);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> errorInesperado(Exception ex) {
        log.error("Error inesperado al procesar la peticion", ex);
        return conCorrelationId(HttpStatus.INTERNAL_SERVER_ERROR, MENSAJE_ERROR_INESPERADO);
    }

    /**
     * JSON ilegible. Si el fallo esta en el valor de un campo conocido (fecha con formato incorrecto, objeto
     * donde se esperaba texto...) se indica ese campo; si no (JSON roto, cuerpo vacio, campo desconocido),
     * solo un mensaje generico, sin repetir lo que envio el cliente.
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        String campo = campoConValorIncompatible(ex);
        if (campo == null) {
            return handleExceptionInternal(ex, null, headers, status, request);
        }
        return ResponseEntity.badRequest()
                .body(Map.of(CLAVE_ERRORES, Map.of(campo, Set.of(CodigoError.FORMATO_INVALIDO))));
    }

    //Cuerpo comun para las excepciones estandar de Spring MVC: solo un mensaje, nunca la excepcion.
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        Map<String, Object> cuerpo = Map.of(CLAVE_MENSAJE, mensajePara(statusCode));
        return super.handleExceptionInternal(ex, cuerpo, headers, statusCode, request);
    }

    // Recorre las causas buscando el error de Jackson, que es el que sabe en que campo fallo.
    private static String campoConValorIncompatible(HttpMessageNotReadableException ex) {
        for (Throwable causa = ex.getCause(); causa != null; causa = causa.getCause()) {
            if (causa instanceof DatabindException error) {
                boolean campoConocido = !(error instanceof UnrecognizedPropertyException) && !error.getPath().isEmpty();
                return campoConocido ? error.getPath().get(0).getPropertyName() : null;
            }
        }
        return null;
    }

    private static String mensajePara(HttpStatusCode estado) {
        if (estado.is5xxServerError()) {
            return MENSAJE_ERROR_INESPERADO;
        }
        return estado.value() == HttpStatus.CONTENT_TOO_LARGE.value()
                ? MENSAJE_CUERPO_DEMASIADO_GRANDE
                : MENSAJE_PETICION_INVALIDA;
    }

    private static ResponseEntity<Map<String, Object>> conCorrelationId(HttpStatus estado, String mensaje) {
        String correlationId = MDC.get(FiltroCorrelacionId.CLAVE_MDC);
        if (correlationId == null) {
            // Sin el filtro (p. ej. en algunos tests) se genera uno igualmente para no devolver null
            correlationId = UUID.randomUUID().toString();
        }
        return ResponseEntity.status(estado).body(Map.of(CLAVE_MENSAJE, mensaje, "correlationId", correlationId));
    }
}
