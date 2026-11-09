package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashSet;
import java.util.OptionalLong;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.esibuy.esibuy_backend.util.RelojMutable;

/**
 * Calculo y verificacion de codigos TOTP (HdU 2FA, fase 0). Casos: CP-2FA-08 a CP-2FA-25.
 *
 * Parametros fijos por compatibilidad con Google Authenticator: HMAC-SHA1, 6 digitos y pasos de 30 s (la aplicacion
 * ignora cualquier otro valor). Los vectores son los oficiales: RFC 4226 (apendice D, secreto ASCII
 * "12345678901234567890") y RFC 6238 (apendice B, SHA-1), que da 8 digitos; los 6 de aqui son sus 6 ultimos.
 *
 * La verificacion acepta el paso actual y los adyacentes (+-1, por el desfase del reloj del movil) y rechaza un paso
 * ya aceptado (ultimoPaso), para que un codigo no valga dos veces. Devuelve el paso que ha coincidido para que el
 * servicio lo guarde de forma atomica. La comparacion en tiempo constante se revisa en el codigo, no aqui.
 *
 * Terminada: ya no lleva la etiqueta pendiente-2fa y entra en la regresion.
 */
class GeneradorTotpTest {

    private static final byte[] SECRETO_RFC = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    // Codigos del secreto del RFC en los pasos 0, 1, 2 y 3 (RFC 4226, apendice D)
    private static final String PASO_0 = "755224";
    private static final String PASO_1 = "287082";
    private static final String PASO_2 = "359152";
    private static final String PASO_3 = "969429";

    private static Clock relojEn(long segundos) {
        return Clock.fixed(Instant.ofEpochSecond(segundos), ZoneOffset.UTC);
    }

    private static GeneradorTotp generadorEn(long segundos) {
        return new GeneradorTotp(relojEn(segundos));
    }

    /** Una fuente de aleatoriedad que devuelve siempre el mismo byte, para comprobar cuantos bytes se piden. */
    private static SecureRandom aleatorioConstante(byte valor) {
        return new SecureRandom() {
            @Override
            public void nextBytes(byte[] bytes) {
                Arrays.fill(bytes, valor);
            }
        };
    }

    // ---------------------------------------------------------------- parametros

    @Test
    void parametros_sonLosDeGoogleAuthenticator() { // CP-2FA-08
        assertThat(GeneradorTotp.DIGITOS).isEqualTo(6);
        assertThat(GeneradorTotp.PERIODO_SEGUNDOS).isEqualTo(30);
        assertThat(GeneradorTotp.VENTANA).isEqualTo(1);
        assertThat(GeneradorTotp.LONGITUD_SECRETO_BYTES).isEqualTo(20);
    }

    // ---------------------------------------------------------------- calculo

    @ParameterizedTest(name = "contador {0} -> {1}")
    @CsvSource({
            "0,755224", "1,287082", "2,359152", "3,969429", "4,338314",
            "5,254676", "6,287922", "7,162583", "8,399871", "9,520489" })
    void codigo_vectoresDelRfc4226(long contador, String esperado) { // CP-2FA-09
        assertThat(generadorEn(0).codigo(SECRETO_RFC, contador)).isEqualTo(esperado);
    }

    @ParameterizedTest(name = "instante {0} s -> {1}")
    @CsvSource({
            "59,287082",
            "1111111109,081804",
            "1111111111,050471",
            "1234567890,005924",
            "2000000000,279037",
            "20000000000,353130" })
    void codigo_vectoresDelRfc6238_enElPasoDelInstante(long segundos, String esperado) { // CP-2FA-10
        GeneradorTotp generador = generadorEn(segundos);

        assertThat(generador.codigo(SECRETO_RFC, generador.pasoActual())).isEqualTo(esperado);
    }

    @Test
    void codigo_conceroAlPrincipio_conservaLosSeisDigitos() { // CP-2FA-11
        // En 1111111109 s el codigo es 081804: no puede salir como 81804
        GeneradorTotp generador = generadorEn(1111111109L);

        String codigo = generador.codigo(SECRETO_RFC, generador.pasoActual());

        assertThat(codigo).hasSize(6).startsWith("0");
    }

    @Test
    void pasoActual_esElInstanteEnSegundosEntre30() { // CP-2FA-12
        assertThat(generadorEn(0).pasoActual()).isZero();
        assertThat(generadorEn(29).pasoActual()).isZero();
        assertThat(generadorEn(30).pasoActual()).isEqualTo(1);
        assertThat(generadorEn(59).pasoActual()).isEqualTo(1);
        assertThat(generadorEn(60).pasoActual()).isEqualTo(2);
        assertThat(generadorEn(1111111109L).pasoActual()).isEqualTo(37037036L);
    }

    @Test
    void codigo_secretoNuloOCorto_oPasoNegativo_lanzaExcepcion() { // CP-2FA-13
        GeneradorTotp generador = generadorEn(59);

        assertThrows(IllegalArgumentException.class, () -> generador.codigo(null, 1));
        assertThrows(IllegalArgumentException.class, () -> generador.codigo(new byte[0], 1));
        // El RFC 4226 pide al menos 128 bits: 15 bytes no llegan
        assertThrows(IllegalArgumentException.class, () -> generador.codigo(new byte[15], 1));
        assertThrows(IllegalArgumentException.class, () -> generador.codigo(SECRETO_RFC, -1));
    }

    // ---------------------------------------------------------------- verificacion

    @Test
    void verificar_codigoDelPasoActual_devuelveEsePaso() { // CP-2FA-14
        OptionalLong paso = generadorEn(59).verificar(SECRETO_RFC, PASO_1, GeneradorTotp.NINGUN_PASO);

        assertThat(paso).hasValue(1);
    }

    @Test
    void verificar_codigoDelPasoAnteriorYDelSiguiente_seAceptan() { // CP-2FA-15
        GeneradorTotp generador = generadorEn(59);

        assertThat(generador.verificar(SECRETO_RFC, PASO_0, GeneradorTotp.NINGUN_PASO)).hasValue(0);
        assertThat(generador.verificar(SECRETO_RFC, PASO_2, GeneradorTotp.NINGUN_PASO)).hasValue(2);
    }

    @Test
    void verificar_codigoFueraDeLaVentana_seRechaza() { // CP-2FA-16
        // En el paso 1 la ventana cubre 0, 1 y 2; el 3 queda fuera
        assertThat(generadorEn(59).verificar(SECRETO_RFC, PASO_3, GeneradorTotp.NINGUN_PASO)).isEmpty();
    }

    @Test
    void verificar_codigoIncorrecto_seRechaza() { // CP-2FA-17
        assertThat(generadorEn(59).verificar(SECRETO_RFC, "000000", GeneradorTotp.NINGUN_PASO)).isEmpty();
    }

    @Test
    void verificar_codigoDeOtroSecreto_seRechaza() { // CP-2FA-17
        byte[] otroSecreto = "ABCDEFGHIJKLMNOPQRST".getBytes(StandardCharsets.US_ASCII);

        assertThat(generadorEn(59).verificar(otroSecreto, PASO_1, GeneradorTotp.NINGUN_PASO)).isEmpty();
    }

    @Test
    void verificar_alAvanzarElReloj_elCodigoValeHastaSalirDeLaVentana() { // CP-2FA-18
        RelojMutable reloj = new RelojMutable(Instant.ofEpochSecond(59), ZoneOffset.UTC);
        GeneradorTotp generador = new GeneradorTotp(reloj);

        reloj.avanzar(Duration.ofSeconds(30)); // t = 89 -> paso 2: el paso 1 es el anterior
        assertThat(generador.verificar(SECRETO_RFC, PASO_1, GeneradorTotp.NINGUN_PASO)).hasValue(1);

        reloj.avanzar(Duration.ofSeconds(60)); // t = 149 -> paso 4: la ventana es 3, 4 y 5
        assertThat(generador.verificar(SECRETO_RFC, PASO_1, GeneradorTotp.NINGUN_PASO)).isEmpty();
    }

    @Test
    void verificar_pasoYaAceptado_seRechaza() { // CP-2FA-19
        GeneradorTotp generador = generadorEn(59);

        // Ya se acepto el paso 1: el mismo codigo no vale otra vez ni dentro de su ventana
        assertThat(generador.verificar(SECRETO_RFC, PASO_1, 1)).isEmpty();
        // y tampoco uno anterior al ya aceptado
        assertThat(generador.verificar(SECRETO_RFC, PASO_0, 1)).isEmpty();
    }

    @Test
    void verificar_pasoPosteriorAlUltimoAceptado_seAcepta() { // CP-2FA-20
        assertThat(generadorEn(59).verificar(SECRETO_RFC, PASO_2, 1)).hasValue(2);
    }

    @Test
    void verificar_elUltimoPasoDeLaVentanaYaUsado_seRechaza() { // CP-2FA-20
        assertThat(generadorEn(59).verificar(SECRETO_RFC, PASO_2, 2)).isEmpty();
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {
            " ", "12345", "1234567", "28708a", "abcdef", "287-082", "+28708", "-28708", "287082\n", "287.08",
            "٢٨٧٠٨٢", // los mismos digitos escritos en arabe: no son ASCII
            "287082287082" })
    @NullAndEmptySource
    void verificar_formatoQueNoSonSeisDigitos_devuelveVacioSinLanzarExcepcion(String codigo) { // CP-2FA-21
        assertThat(generadorEn(59).verificar(SECRETO_RFC, codigo, GeneradorTotp.NINGUN_PASO)).isEmpty();
    }

    @Test
    void verificar_codigoEscritoConEspacios_seAcepta() { // CP-2FA-22
        // Google Authenticator muestra el codigo como "287 082"; quien lo copia tal cual no debe fallar
        GeneradorTotp generador = generadorEn(59);

        assertThat(generador.verificar(SECRETO_RFC, "287 082", GeneradorTotp.NINGUN_PASO)).hasValue(1);
        assertThat(generador.verificar(SECRETO_RFC, " 287082 ", GeneradorTotp.NINGUN_PASO)).hasValue(1);
    }

    @Test
    void verificar_secretoNuloOCorto_lanzaExcepcion() { // CP-2FA-23
        GeneradorTotp generador = generadorEn(59);

        assertThrows(IllegalArgumentException.class,
                () -> generador.verificar(null, PASO_1, GeneradorTotp.NINGUN_PASO));
        assertThrows(IllegalArgumentException.class,
                () -> generador.verificar(new byte[15], PASO_1, GeneradorTotp.NINGUN_PASO));
    }

    // ---------------------------------------------------------------- secreto nuevo

    @Test
    void nuevoSecreto_tiene160BitsYPideExactamenteEsosBytesALaFuenteAleatoria() { // CP-2FA-24
        assertThat(generadorEn(0).nuevoSecreto()).hasSize(20);
        assertThat(new GeneradorTotp(relojEn(0), aleatorioConstante((byte) 0x00)).nuevoSecreto())
                .containsOnly((byte) 0x00).hasSize(20);
        assertThat(new GeneradorTotp(relojEn(0), aleatorioConstante((byte) 0xFF)).nuevoSecreto())
                .containsOnly((byte) 0xFF).hasSize(20);
    }

    @Test
    void nuevoSecreto_milSecretosSonTodosDistintos() { // CP-2FA-25
        GeneradorTotp generador = generadorEn(0);
        Set<String> secretos = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            secretos.add(Base32.codificar(generador.nuevoSecreto()));
        }

        assertThat(secretos).hasSize(1000);
    }
}
