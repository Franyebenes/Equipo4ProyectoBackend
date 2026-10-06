package com.esibuy.esibuy_backend.integracion;

import static org.junit.jupiter.api.Assertions.fail;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Login contra MongoDB real y Argon2id real. Necesita Docker en marcha.
 *
 * Orden TDD (plan de pruebas, sección 8): paso 7.
 * Casos: CP-LIN-01, CP-LIN-03, CP-LIN-05, CP-LIN-07, CP-LIN-08, CP-LIN-12.
 * Colaboradores: Solo ValidadorDominioEmail (para el registro previo) y un reloj mutable
 * (@TestConfiguration). Reutilizar el patrón de RegistroIntegracionMongoTest sin
 * modificarlo.
 *
 * Esqueleto: cada test está sin escribir. Se desarrolla con el ciclo Red-Green-Refactor:
 * primero el test (Arrange, Act, Assert), después el código mínimo que lo hace pasar.
 * Al terminar la clase, quitar la etiqueta @Tag("pendiente-login").
 */
@Tag("pendiente-login")
class LoginIntegracionMongoTest {

    @Test
    void login_tras_registrarseConLasMismasCredenciales_devuelve200YCreaLaSesion() { // CP-LIN-01
        /* TODO:
         * Registrar un usuario con el endpoint de registro y después iniciar sesión con las mismas
         * credenciales: 200 y sesión creada. Demuestra que registro y login usan el mismo pepper.
         */
        fail("no implementado");
    }

    @Test
    void login_contrasenaIncorrectaYCorreoNoRegistrado_respondenExactamenteIgual() { // CP-LIN-03
        /* TODO:
         * Contraseña incorrecta frente a correo no registrado: 401 en ambos con el mismo cuerpo y las
         * mismas cabeceras (salvo correlationId y fecha). Ningún cuerpo contiene $argon2id ni el hash.
         */
        fail("no implementado");
    }

    @Test
    void login_usuarioConStatusBlocked_seRechazaIgualQueCredencialesIncorrectasYTrasActivarlo_entra() { // CP-LIN-05
        /* TODO:
         * Usuario con status BLOCKED: 401 idéntico al de credenciales incorrectas. Tras pasarlo a ACTIVE:
         * login correcto.
         */
        fail("no implementado");
    }

    @Test
    void login_inyeccionNoSqlConLaColeccionLlena_noAutenticaANadie() { // CP-LIN-07
        /* TODO:
         * Con la colección llena de usuarios, probar {"email":{"$ne":null}, ...} y correo con texto
         * {"$gt":""}: no se autentica a nadie (400 o 401).
         */
        fail("no implementado");
    }

    @Test
    void login_fuerzaBrutaConRelojMutable_bloqueaYSeRecuperaAlAvanzarElReloj() { // CP-LIN-08
        /* TODO:
         * Con reloj mutable: cinco fallos, intento correcto durante el bloqueo y avance del reloj. Debe
         * dar 429 con Retry-After; el intento correcto durante el bloqueo sigue dando 429; al avanzar el
         * reloj, login correcto y contador reiniciado.
         */
        fail("no implementado");
    }

    @Test
    void login_passwordHashSinFormatoValido_devuelve401SinErrorInterno() { // CP-LIN-12
        /* TODO:
         * Documento con passwordHash sin formato de hash válido, con el codificador real: 401 y error
         * interno registrado, sin error 500.
         */
        fail("no implementado");
    }
}
