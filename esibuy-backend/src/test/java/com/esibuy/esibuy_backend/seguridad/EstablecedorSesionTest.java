package com.esibuy.esibuy_backend.seguridad;

import static org.junit.jupiter.api.Assertions.fail;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Creación y rotación de la sesión tras un login correcto.
 *
 * Orden TDD (plan de pruebas, sección 8): paso 5.
 * Casos: CP-SES-01, CP-SES-02.
 * Colaboradores: MockHttpServletRequest y MockHttpSession; Clock fijo.
 *
 * Esqueleto: cada test está sin escribir. Se desarrolla con el ciclo Red-Green-Refactor:
 * primero el test (Arrange, Act, Assert), después el código mínimo que lo hace pasar.
 * Al terminar la clase, quitar la etiqueta @Tag("pendiente-login").
 */
@Tag("pendiente-login")
class EstablecedorSesionTest {

    @Test
    void establecerSesion_loginCorrectoDeCadaRol_creaContextoAutenticadoConLaPoliticaDelRol() { // CP-SES-01
        /* TODO:
         * Parametrizar por rol. Debe crear la sesión con contexto de seguridad autenticado y una única
         * autoridad ROLE_xxx, aplicar la inactividad máxima del rol (20 min CUSTOMER y PREMIUM, 15 min
         * SELLER y ADMIN) y guardar el instante de inicio con la hora del Clock.
         */
        fail("no implementado");
    }

    @Test
    void establecerSesion_existiaSesionPreviaAnonimaODeOtroUsuario_rotaElIdentificador() { // CP-SES-02
        /* TODO:
         * Parametrizar: sesión previa anónima o de otro usuario. El identificador debe cambiar tras el
         * login (anti session fixation), no heredar atributos antiguos y dejar invalidada la sesión
         * anterior.
         */
        fail("no implementado");
    }
}
