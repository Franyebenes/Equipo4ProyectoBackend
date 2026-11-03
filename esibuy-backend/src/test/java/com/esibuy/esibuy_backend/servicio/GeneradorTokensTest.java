package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;

/**
 * Generacion del token y calculo de su hash (HdU recuperacion de contrasena, fase 1).
 * Casos: CP-TOK-01 a CP-TOK-06.
 *
 * El token son 256 bits aleatorios en base64url sin relleno (43 caracteres), que es justo el formato que acepta
 * PlantillasCorreo. En la base de datos solo se guarda su SHA-256 en hexadecimal: aunque se filtrara la coleccion,
 * con los hashes no se puede construir un enlace valido.
 *
 * Terminada: ya no lleva la etiqueta pendiente-recuperacion y entra en la regresion.
 */
class GeneradorTokensTest {

    private final GeneradorTokens generador = new GeneradorTokens();

    /** Una fuente de aleatoriedad que devuelve siempre el mismo byte, para comprobar cuanta entropia se pide. */
    private static SecureRandom aleatorioConstante(byte valor) {
        return new SecureRandom() {
            @Override
            public void nextBytes(byte[] bytes) {
                Arrays.fill(bytes, valor);
            }
        };
    }

    @Test
    void nuevoToken_tiene43CaracteresBase64UrlSinRelleno() { // CP-TOK-01
        String token = generador.nuevoToken();

        assertThat(token).matches("^[A-Za-z0-9_-]{43}$");
    }

    @Test
    void nuevoToken_pideExactamente256BitsALaFuenteAleatoria() { // CP-TOK-02
        // 32 bytes a cero en base64url son 43 'A'; 32 bytes a 0xFF son 42 '_' y un '8' final
        assertThat(new GeneradorTokens(aleatorioConstante((byte) 0x00)).nuevoToken()).isEqualTo("A".repeat(43));
        assertThat(new GeneradorTokens(aleatorioConstante((byte) 0xFF)).nuevoToken()).isEqualTo("_".repeat(42) + "8");
    }

    @Test
    void nuevoToken_milTokensSonTodosDistintos() { // CP-TOK-03
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            tokens.add(generador.nuevoToken());
        }

        assertThat(tokens).hasSize(1000);
    }

    @Test
    void hash_esSha256EnHexadecimalMinuscula() { // CP-TOK-04
        // Vector de prueba estandar de SHA-256
        assertThat(GeneradorTokens.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void hash_esDeterministaYDistintoParaTokensDistintos() { // CP-TOK-05
        String uno = generador.nuevoToken();
        String otro = generador.nuevoToken();

        assertThat(GeneradorTokens.hash(uno)).isEqualTo(GeneradorTokens.hash(uno)).matches("^[0-9a-f]{64}$");
        assertThat(GeneradorTokens.hash(uno)).isNotEqualTo(GeneradorTokens.hash(otro));
        // y el hash no contiene el token
        assertThat(GeneradorTokens.hash(uno)).doesNotContain(uno);
    }

    @ParameterizedTest(name = "hash de [{0}]")
    @NullAndEmptySource
    void hash_tokenNuloOVacio_seRechaza(String token) { // CP-TOK-05
        assertThrows(IllegalArgumentException.class, () -> GeneradorTokens.hash(token));
    }

    @Test
    void tokenEmitido_toString_nuncaIncluyeElToken() { // CP-TOK-06
        TokenEmitido emitido = new TokenEmitido("TOKEN-SECRETO-9876543210", Instant.parse("2026-10-16T10:00:00Z"),
                Instant.parse("2026-10-16T10:15:00Z"));

        assertThat(emitido.toString()).doesNotContain("TOKEN-SECRETO-9876543210");
    }
}
