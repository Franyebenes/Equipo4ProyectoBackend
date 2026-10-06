package com.esibuy.esibuy_backend.seguridad;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pruebas del pepper y de la configuracion real del codificador de contrasenas.
 */
class CodificadorContrasenaTest {

    private static final String CONTRASENA = "Tren-Azul-Lluvia-77";

    // Formato de Spring Security: $argon2id$v=19$m=<memoria KiB>,t=<iteraciones>,p=<paralelismo>$salt$hash
    private static final Pattern PARAMETROS_ARGON2 =
            Pattern.compile("^\\$argon2id\\$v=\\d+\\$m=(\\d+),t=(\\d+),p=(\\d+)\\$.+");

    /** Delegado de bajo coste, solo para las pruebas que no miden los parametros de produccion. */
    private static PasswordEncoder delegadoRapido() {
        return new Argon2PasswordEncoder(16, 32, 1, 1 << 10, 1);
    }

    @Test
    void matches_hashCreadoConUnPepperYVerificadoConOtro_noVerifica() { // CP-REG-49
        // Given
        PasswordEncoder codificadorConPepperA = new CodificadorContrasenaConPepper("pepper-A", delegadoRapido());
        PasswordEncoder codificadorConPepperB = new CodificadorContrasenaConPepper("pepper-B", delegadoRapido());

        // When
        String hash = codificadorConPepperA.encode(CONTRASENA);

        // Then
        assertThat(codificadorConPepperA.matches(CONTRASENA, hash)).isTrue();
        assertThat(codificadorConPepperB.matches(CONTRASENA, hash)).isFalse();
    }

    @Test
    void matches_hashCreadoConPepperYVerificadoSinEl_noVerifica() { // CP-REG-49
        // Given: un atacante con la BBDD pero sin el pepper intenta verificar directamente con Argon2id
        PasswordEncoder codificadorConPepper = new CodificadorContrasenaConPepper("pepper-A", delegadoRapido());
        PasswordEncoder argon2SinPepper = delegadoRapido();

        // When
        String hash = codificadorConPepper.encode(CONTRASENA);

        // Then
        assertThat(argon2SinPepper.matches(CONTRASENA, hash)).isFalse();
    }

    @ParameterizedTest(name = "pepper invalido: \"{0}\"")
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    void codificadorContrasena_pepperAusenteOVacio_fallaAlArrancarSinUsarValorPorDefecto( // CP-REG-50
            String pepperInvalido) {
        // Given
        ConfiguracionContrasena configuracion = new ConfiguracionContrasena();

        // When / Then
        assertThrows(IllegalStateException.class, () -> configuracion.codificadorContrasena(pepperInvalido));
    }

    @Test
    void codificadorContrasena_configuracionDeProduccion_cumpleLosRangosDeArgon2idDelDocumento() { // CP-REG-51
        // Given: el codificador tal y como se configura de verdad (sin coste reducido)
        PasswordEncoder codificador = new ConfiguracionContrasena().codificadorContrasena("pepper-de-prueba");

        // When
        String hash = codificador.encode(CONTRASENA);

        // Then
        Matcher parametros = PARAMETROS_ARGON2.matcher(hash);
        assertThat(parametros.matches()).as("el hash debe ser Argon2id con parametros visibles").isTrue();

        int memoriaKib = Integer.parseInt(parametros.group(1));
        int iteraciones = Integer.parseInt(parametros.group(2));
        int paralelismo = Integer.parseInt(parametros.group(3));

        assertThat(memoriaKib).as("memoria entre 64 y 256 MB").isBetween(64 * 1024, 256 * 1024);
        assertThat(iteraciones).as("iteraciones entre 2 y 4").isBetween(2, 4);
        assertThat(paralelismo).as("paralelismo entre 1 y 2").isBetween(1, 2);
        assertThat(codificador.matches(CONTRASENA, hash)).isTrue();
    }
}
