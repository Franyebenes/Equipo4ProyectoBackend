package com.esibuy.esibuy_backend.controlador;

import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Esqueleto vacio: todavia no mapea ninguna excepcion.
 *
 * TODO (tests: ControladorAuthTest, CP-CTR-02 a CP-CTR-05, CP-SEG-09, CP-SEG-10, CP-REG-36):
 *  - DatosRegistroInvalidosException -> 400 {"errores": {campo: [codigos]}}
 *  - JSON ilegible o fecha con formato incorrecto -> 400 (con errores.fechaNacimiento en el caso de la fecha)
 *  - RegistroNoCompletadoException -> 409 {"mensaje": MENSAJE_GENERICO}
 *  - ServicioNoDisponibleException -> 503 con mensaje generico
 *  - Content-Type no soportado -> 415
 *  - Cualquier otra excepcion -> 500 {"mensaje", "correlationId"} y el detalle completo SOLO en el log
 */
@RestControllerAdvice
public class ManejadorExcepciones {
}
