package com.esibuy.esibuy_backend.servicio;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Enmascarado y saneado de datos antes de escribirlos en el log.
 *
 * Orden TDD (plan de pruebas, sección 8): paso 4.
 * Casos: CP-AUD-06.
 * Colaboradores: Ninguno: función pura.
 *
 * Esqueleto: cada test está sin escribir. Se desarrolla con el ciclo Red-Green-Refactor:
 * primero el test (Arrange, Act, Assert), después el código mínimo que lo hace pasar.
 * Al terminar la clase, quitar la etiqueta @Tag("pendiente-login").
 */
@Tag("pendiente-login")
class EnmascaradorDatosLogTest {

    @Test
    void enmascarar_correosYTextosConCaracteresDeControl_ocultaDatosYEvitaLogForging() { // CP-AUD-06
        /* TODO:
         * Parametrizar con correos y textos con saltos de línea o caracteres de control. Debe conservar el
         * primer carácter y parte del dominio, ocultar el resto y no permitir falsificar una línea de log.
         */
        fail("no implementado");
    }
}
