package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Codigos de recuperacion del 2FA (HdU 2FA, fase 0). Casos: CP-2FA-51 a CP-2FA-65.
 *
 * Son la salida de emergencia de quien pierde el movil: un solo uso cada uno, de 80 bits (16 caracteres Base32) y se
 * muestran agrupados de cuatro en cuatro (ABCD-EFGH-IJKL-MNOP) para copiarlos bien. En la base de datos solo se guarda
 * su HMAC-SHA-256 con el pepper: con 80 bits un hash rapido es seguro y comprobar varios codigos no resulta caro, a
 * diferencia de Argon2id. El HMAC lleva un prefijo propio del uso para que su hash no coincida nunca con otro HMAC
 * calculado con el mismo pepper (el de las contrasenas, por ejemplo).
 *
 * Terminada: ya no lleva la etiqueta pendiente-2fa y entra en la regresion.
 */
class GeneradorCodigosRecuperacionTest {

    private static final String PEPPER = "pepper-de-pruebas-para-los-codigos";

    private final GeneradorCodigosRecuperacion generador = new GeneradorCodigosRecuperacion(PEPPER);

    /** Una fuente de aleatoriedad que devuelve siempre el mismo byte, para comprobar cuantos bits se piden. */
    private static SecureRandom aleatorioConstante(byte valor) {
        return new SecureRandom() {
            @Override
            public void nextBytes(byte[] bytes) {
                Arrays.fill(bytes, valor);
            }
        };
    }

    // ---------------------------------------------------------------- generacion

    @Test
    void nuevoCodigo_tiene16CaracteresBase32() { // CP-2FA-51
        assertThat(generador.nuevoCodigo()).matches("^[A-Z2-7]{16}$");
    }

    @Test
    void nuevoCodigo_pideExactamente80BitsALaFuenteAleatoria() { // CP-2FA-52
        // 10 bytes a cero son 16 'A'; 10 bytes a 0xFF son 16 '7' (el ultimo simbolo del alfabeto)
        assertThat(new GeneradorCodigosRecuperacion(PEPPER, aleatorioConstante((byte) 0x00)).nuevoCodigo())
                .isEqualTo("A".repeat(16));
        assertThat(new GeneradorCodigosRecuperacion(PEPPER, aleatorioConstante((byte) 0xFF)).nuevoCodigo())
                .isEqualTo("7".repeat(16));
    }

    @Test
    void nuevosCodigos_devuelveLaCantidadPedidaYTodosDistintos() { // CP-2FA-53
        List<String> codigos = generador.nuevosCodigos(10);

        assertThat(codigos).hasSize(10).doesNotHaveDuplicates().allMatch(c -> c.matches("^[A-Z2-7]{16}$"));
    }

    @Test
    void nuevoCodigo_milCodigosSonTodosDistintos() { // CP-2FA-53
        Set<String> codigos = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            codigos.add(generador.nuevoCodigo());
        }

        assertThat(codigos).hasSize(1000);
    }

    @ParameterizedTest(name = "cantidad {0}")
    @ValueSource(ints = { 0, -1, 21, 1000 })
    void nuevosCodigos_cantidadFueraDeRango_lanzaExcepcion(int cantidad) { // CP-2FA-54
        assertThrows(IllegalArgumentException.class, () -> generador.nuevosCodigos(cantidad));
    }

    // ---------------------------------------------------------------- formato y normalizacion

    @Test
    void formatear_agrupaDeCuatroEnCuatroConGuiones() { // CP-2FA-55
        assertThat(GeneradorCodigosRecuperacion.formatear("ABCDEFGH23456723")).isEqualTo("ABCD-EFGH-2345-6723");
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {
            "ABCDEFGH23456723",
            "ABCD-EFGH-2345-6723",
            "abcd-efgh-2345-6723",
            " ABCD EFGH 2345 6723 ",
            "abcdefgh23456723",
            "ABCD-EFGH 2345-6723" })
    void normalizar_variasFormasDelMismoCodigo_danLaFormaCanonica(String entrada) { // CP-2FA-56
        assertThat(GeneradorCodigosRecuperacion.normalizar(entrada)).isEqualTo("ABCDEFGH23456723");
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {
            "ABCDEFGH2345672", // 15 caracteres
            "ABCDEFGH234567234", // 17
            "ABCDEFGH2345671!", // simbolo
            "ABCDEFGH23456781", // 1 y 8 no estan en el alfabeto
            "ABCDEFGH234567\n3",
            "АBCDEFGH23456723" // una A cirilica que se parece a la latina
    })
    @NullAndEmptySource
    void normalizar_cosasQueNoSonUnCodigo_devuelveNulo(String entrada) { // CP-2FA-57
        assertThat(GeneradorCodigosRecuperacion.normalizar(entrada)).isNull();
    }

    // ---------------------------------------------------------------- hash

    @Test
    void hash_esHmacSha256EnHexadecimalMinuscula() { // CP-2FA-58
        assertThat(generador.hash("ABCDEFGH23456723")).matches("^[0-9a-f]{64}$");
    }

    @Test
    void hash_esDeterminista_yIgualParaCualquierFormaDeEscribirElCodigo() { // CP-2FA-59
        String esperado = generador.hash("ABCDEFGH23456723");

        assertThat(generador.hash("ABCDEFGH23456723")).isEqualTo(esperado);
        assertThat(generador.hash("abcd-efgh-2345-6723")).isEqualTo(esperado);
        assertThat(generador.hash(" ABCD EFGH 2345 6723 ")).isEqualTo(esperado);
    }

    @Test
    void hash_codigosDistintosDanHashesDistintos_yNoContieneElCodigo() { // CP-2FA-59
        String uno = generador.nuevoCodigo();
        String otro = generador.nuevoCodigo();

        assertThat(generador.hash(uno)).isNotEqualTo(generador.hash(otro)).doesNotContain(uno.toLowerCase());
    }

    @Test
    void hash_dependeDelPepper() { // CP-2FA-60
        String conUno = new GeneradorCodigosRecuperacion("un-pepper").hash("ABCDEFGH23456723");
        String conOtro = new GeneradorCodigosRecuperacion("otro-pepper").hash("ABCDEFGH23456723");

        assertThat(conUno).isNotEqualTo(conOtro);
    }

    @Test
    void hash_noEsUnSha256SinClave_niElHmacDirectoDelPepper() throws Exception { // CP-2FA-61
        String codigo = "ABCDEFGH23456723";
        byte[] sinClave = MessageDigest.getInstance("SHA-256").digest(codigo.getBytes(StandardCharsets.UTF_8));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(PEPPER.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hmacDirecto = mac.doFinal(codigo.getBytes(StandardCharsets.UTF_8));

        String hash = generador.hash(codigo);

        assertThat(hash).isNotEqualTo(HexFormat.of().formatHex(sinClave));
        // Separacion de usos: un HMAC del mismo mensaje con el mismo pepper (como el de las contrasenas) es otro valor
        assertThat(hash).isNotEqualTo(HexFormat.of().formatHex(hmacDirecto));
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = { "ABCDEFGH2345672", "no es un codigo", "ABCDEFGH2345671!" })
    @NullAndEmptySource
    void hash_cosasQueNoSonUnCodigo_lanzaExcepcion(String entrada) { // CP-2FA-62
        assertThrows(IllegalArgumentException.class, () -> generador.hash(entrada));
    }

    // ---------------------------------------------------------------- comprobacion

    @Test
    void coincide_codigoCorrecto_enCualquierForma() { // CP-2FA-63
        String hash = generador.hash("ABCDEFGH23456723");

        assertThat(generador.coincide("ABCDEFGH23456723", hash)).isTrue();
        assertThat(generador.coincide("abcd-efgh-2345-6723", hash)).isTrue();
    }

    @Test
    void coincide_codigoDistinto_oHashAjeno_esFalso() { // CP-2FA-64
        String hash = generador.hash("ABCDEFGH23456723");

        assertThat(generador.coincide("ABCDEFGH23456724", hash)).isFalse();
        assertThat(generador.coincide("ABCDEFGH23456723",
                new GeneradorCodigosRecuperacion("otro-pepper").hash("ABCDEFGH23456723"))).isFalse();
    }

    @Test
    void coincide_entradasMalformadas_devuelveFalsoSinLanzarExcepcion() { // CP-2FA-64
        String hash = generador.hash("ABCDEFGH23456723");

        assertThat(generador.coincide(null, hash)).isFalse();
        assertThat(generador.coincide("", hash)).isFalse();
        assertThat(generador.coincide("corto", hash)).isFalse();
        assertThat(generador.coincide("ABCDEFGH23456723", null)).isFalse();
        assertThat(generador.coincide("ABCDEFGH23456723", "")).isFalse();
        assertThat(generador.coincide("ABCDEFGH23456723", "no-es-un-hash")).isFalse();
    }

    // ---------------------------------------------------------------- construccion

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { " ", "   " })
    void crear_sinPepper_lanzaExcepcion(String pepper) { // CP-2FA-65
        // Igual que el codificador de contrasenas: sin pepper la aplicacion no debe arrancar
        assertThrows(IllegalStateException.class, () -> new GeneradorCodigosRecuperacion(pepper));
    }
}
