package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Codificacion Base32 del secreto TOTP (HdU 2FA, fase 0). Casos: CP-2FA-01 a CP-2FA-07.
 *
 * Es el formato en el que las aplicaciones autenticadoras (Google Authenticator entre ellas) reciben el secreto, tanto
 * dentro del QR como escrito a mano: alfabeto A-Z y 2-7, sin relleno '='. Se usa tambien para los codigos de
 * recuperacion. Los vectores son los del RFC 4648, apartado 10.
 *
 * Terminada: ya no lleva la etiqueta pendiente-2fa y entra en la regresion.
 */
class Base32Test {

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource(value = {
            "'',''",
            "f,MY",
            "fo,MZXQ",
            "foo,MZXW6",
            "foob,MZXW6YQ",
            "fooba,MZXW6YTB",
            "foobar,MZXW6YTBOI" })
    void codificar_vectoresDelRfc4648_sinRelleno(String texto, String esperado) { // CP-2FA-01
        assertThat(Base32.codificar(texto.getBytes(StandardCharsets.US_ASCII))).isEqualTo(esperado);
    }

    @ParameterizedTest(name = "{1} -> \"{0}\"")
    @CsvSource(value = {
            "'',''",
            "f,MY",
            "fo,MZXQ",
            "foo,MZXW6",
            "foob,MZXW6YQ",
            "fooba,MZXW6YTB",
            "foobar,MZXW6YTBOI" })
    void decodificar_vectoresDelRfc4648(String esperado, String base32) { // CP-2FA-02
        assertThat(Base32.decodificar(base32)).isEqualTo(esperado.getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    void codificarYDecodificar_cualquierLongitud_devuelveLoMismo() { // CP-2FA-03
        SecureRandom aleatorio = new SecureRandom();
        for (int longitud = 0; longitud <= 64; longitud++) {
            byte[] original = new byte[longitud];
            aleatorio.nextBytes(original);

            String codificado = Base32.codificar(original);

            assertThat(codificado).matches("^[A-Z2-7]*$").doesNotContain("=");
            assertThat(Base32.decodificar(codificado)).isEqualTo(original);
        }
    }

    @Test
    void decodificar_minusculas_seAceptan() { // CP-2FA-04
        assertThat(Base32.decodificar("mzxw6ytboi")).isEqualTo("foobar".getBytes(StandardCharsets.US_ASCII));
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {
            "MZXW1", // el 1 no esta en el alfabeto
            "MZXW0", // ni el 0
            "MZXW8", // ni el 8
            "MZXW9", // ni el 9
            "MZXQ====", // el relleno no se admite: el secreto se maneja siempre sin el
            "MZ XQ", // ni los espacios
            "MZXQ-", // ni los guiones
            "MZXQ\n", // ni los saltos de linea
            "MZXÑ", // ni letras fuera de ASCII
            "MZ\u0000Q" })
    void decodificar_caracteresFueraDelAlfabeto_lanzaExcepcion(String entrada) { // CP-2FA-05
        assertThrows(IllegalArgumentException.class, () -> Base32.decodificar(entrada));
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {
            "M", // 5 bits: no llegan a un byte
            "MZX", // 15 bits
            "MZXW6Y", // 30 bits
            "MZXW6YTBO" // un caracter de mas sobre un bloque completo de 8
    })
    void decodificar_longitudImposible_lanzaExcepcion(String entrada) { // CP-2FA-06
        assertThrows(IllegalArgumentException.class, () -> Base32.decodificar(entrada));
    }

    @Test
    void decodificar_bitsSobrantesDistintosDeCero_lanzaExcepcion() { // CP-2FA-06
        // "MY" es 'f'; "MZ" tendria los 3 bits sobrantes a 001. Un mismo secreto debe tener una sola representacion.
        assertThrows(IllegalArgumentException.class, () -> Base32.decodificar("MZ"));
    }

    @Test
    void nulos_lanzanExcepcion() { // CP-2FA-07
        assertThrows(IllegalArgumentException.class, () -> Base32.codificar(null));
        assertThrows(IllegalArgumentException.class, () -> Base32.decodificar(null));
    }
}
