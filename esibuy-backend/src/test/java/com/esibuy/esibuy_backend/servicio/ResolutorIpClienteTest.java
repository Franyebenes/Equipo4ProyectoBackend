package com.esibuy.esibuy_backend.servicio;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Cómo se decide la IP del cliente para los límites por IP.
 *
 * Orden TDD (plan de pruebas, sección 8): paso 2.
 * Casos: CP-BLQ-16.
 * Colaboradores: HttpServletRequest simulado (MockHttpServletRequest) y la lista de proxies de confianza
 * configurada.
 *
 * Esqueleto: cada test está sin escribir. Se desarrolla con el ciclo Red-Green-Refactor:
 * primero el test (Arrange, Act, Assert), después el código mínimo que lo hace pasar.
 * Al terminar la clase, quitar la etiqueta @Tag("pendiente-login").
 */
@Tag("pendiente-login")
class ResolutorIpClienteTest {

    @Test
    void resolver_cabeceraXForwardedForDeConexionNoConfiable_seIgnoraYUsaLaIpDeLaConexion() { // CP-BLQ-16
        /* TODO:
         * Con X-Forwarded-For enviada desde una conexión no confiable debe ignorarse y usarse la IP de la
         * conexión. Desde un proxy de confianza configurado, usar la IP del cliente (D20).
         */
        fail("no implementado");
    }
}
