package com.esibuy.esibuy_backend.seguridad;

import static org.junit.jupiter.api.Assertions.fail;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Caducidad absoluta de la sesión según el rol.
 *
 * Orden TDD (plan de pruebas, sección 8): paso 5.
 * Casos: CP-SES-06.
 * Colaboradores: Clock fijo; MockHttpSession.
 *
 * Esqueleto: cada test está sin escribir. Se desarrolla con el ciclo Red-Green-Refactor:
 * primero el test (Arrange, Act, Assert), después el código mínimo que lo hace pasar.
 * Al terminar la clase, quitar la etiqueta @Tag("pendiente-login").
 */
@Tag("pendiente-login")
class PoliticaSesionTest {

    @Test
    void haCaducado_limiteAbsolutoPorRol_caducaExactamenteEnElLimiteAunqueHayaActividad() { // CP-SES-06
        /* TODO:
         * Parametrizar por rol: 8 h para CUSTOMER y PREMIUM, 6 h para SELLER y ADMIN. Un segundo antes
         * sigue vigente y en el límite caduca aunque haya actividad reciente.
         */
        fail("no implementado");
    }
}
