package com.esibuy.esibuy_backend.integracion;

import static org.junit.jupiter.api.Assertions.fail;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Cookie y fijación de sesión con servidor real (RANDOM_PORT), porque MockMvc no aplica la
 * configuración de cookies del contenedor. Necesita Docker en marcha.
 *
 * Orden TDD (plan de pruebas, sección 8): paso 7.
 * Casos: CP-SES-13, CP-SES-14.
 * Colaboradores: Solo ValidadorDominioEmail. Servidor real, MongoDB real y Argon2id real.
 *
 * Esqueleto: cada test está sin escribir. Se desarrolla con el ciclo Red-Green-Refactor:
 * primero el test (Arrange, Act, Assert), después el código mínimo que lo hace pasar.
 * Al terminar la clase, quitar la etiqueta @Tag("pendiente-login").
 */
@Tag("pendiente-login")
class LoginSesionIntegracionTest {

    @Test
    void login_cookieRealEmitida_tieneLasBanderasDeSeguridadYValorOpaco() { // CP-SES-13
        /* TODO:
         * Set-Cookie con HttpOnly, Secure y SameSite=Lax, ruta acotada y valor opaco sin correo, rol ni
         * hash. Seguimiento de sesión solo por cookie: ningún identificador en URLs.
         */
        fail("no implementado");
    }

    @Test
    void login_conCookieDeSesionPreestablecida_rotaElIdentificadorYInvalidaLaAntigua() { // CP-SES-14
        /* TODO:
         * Cliente con una cookie de sesión preestablecida que inicia sesión: recibe una cookie nueva con
         * otro identificador y la antigua deja de ser válida (una petición con ella da 401).
         */
        fail("no implementado");
    }
}
