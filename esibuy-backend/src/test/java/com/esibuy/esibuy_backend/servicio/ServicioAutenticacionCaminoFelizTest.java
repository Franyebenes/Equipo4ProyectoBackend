package com.esibuy.esibuy_backend.servicio;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Camino feliz de la autenticación.
 *
 * Orden TDD (plan de pruebas, sección 8): paso 3.
 * Casos: CP-LOG-01, CP-LOG-05, CP-LOG-06, CP-LOG-07.
 * Colaboradores: RepositorioUsuario, PasswordEncoder, LimitadorIntentosLogin y AuditoriaSeguridad
 * mockeados; Clock fijo.
 *
 * Esqueleto: cada test está sin escribir. Se desarrolla con el ciclo Red-Green-Refactor:
 * primero el test (Arrange, Act, Assert), después el código mínimo que lo hace pasar.
 * Al terminar la clase, quitar la etiqueta @Tag("pendiente-login").
 */
@Tag("pendiente-login")
class ServicioAutenticacionCaminoFelizTest {

    @Test
    void autenticar_credencialesCorrectasDeCadaTipoDeUsuario_devuelveResultadoConRolUnico() { // CP-LOG-01
        /* TODO:
         * Parametrizar con cliente normal, premium, vendedor y administrador. Debe devolver id, email,
         * nombre y un único rol (CUSTOMER, PREMIUM, SELLER o ADMIN). Premium nunca como CUSTOMER más
         * PREMIUM.
         */
        fail("no implementado");
    }

    @Test
    void autenticar_correoConMayusculasYEspacios_loNormalizaAntesDeBuscarEnLaBbdd() { // CP-LOG-05
        /* TODO:
         * Con el correo '  ANA@Ejemplo.ES ' debe consultar el repositorio con 'ana@ejemplo.es' (trim y
         * minúsculas) y autenticar con normalidad.
         */
        fail("no implementado");
    }

    @Test
    void autenticar_contrasenaEnNfcOEnNfd_iniciaSesionConLaMismaNormalizacionQueElRegistro() { // CP-LOG-06
        /* TODO:
         * Contraseña registrada en NFC e introducida en NFD (y al revés): login correcto. El codificador
         * debe recibir la contraseña normalizada a NFC, igual que en el registro (CP-PWD-11).
         */
        fail("no implementado");
    }

    @Test
    void autenticar_loginCorrectoTrasFallosPrevios_reiniciaLosContadoresDelCorreo() { // CP-LOG-07
        /* TODO:
         * Login correcto tras menos de 5 fallos previos: debe llamar a registrarExito del limitador con el
         * correo normalizado.
         */
        fail("no implementado");
    }
}
