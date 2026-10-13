package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Enmascarado y saneado de datos antes de escribirlos en el log.
 *
 * Orden TDD (plan de pruebas, sección 8): paso 4.
 * Casos: CP-AUD-06.
 * Colaboradores: Ninguno: función pura.
 *
 * Reglas que fijan estas pruebas:
 *  - correo(): conserva el primer carácter de la parte local y el primero del dominio, y de este último solo la
 *    terminación (.es, .com) si es puramente alfabética; todo lo demás se sustituye por ***. Lo que no es un correo
 *    se reduce a su primer carácter más ***. Nunca sale un carácter de control.
 *  - texto(): para datos no secretos pero controlados por el cliente (user agent, IP); sustituye por _ cada carácter
 *    de control o separador de línea, de modo que no se pueda falsificar una línea de log.
 *
 * Desarrollada con el ciclo Red-Green-Refactor: primero el test (Arrange, Act, Assert), después el código
 * mínimo que lo hace pasar. Terminada: ya no lleva la etiqueta pendiente-login y entra en la regresión.
 */
class EnmascaradorDatosLogTest {

    private static final String SIN_DATO = "(sin correo)";

    static Stream<Arguments> correosYSuEnmascarado() {
        return Stream.of(
                arguments("ana.garcia@ejemplo.es", "a***@e***.es"),
                arguments("Ana@Ejemplo.COM", "A***@E***.COM"),
                arguments("a@b.es", "a***@b***.es"),
                // Dominio sin punto: no hay terminación que conservar
                arguments("ana@localhost", "a***@l***"),
                // Lo que no tiene forma de correo se reduce a su primer carácter
                arguments("sin-arroba", "s***"),
                arguments("{\"$ne\":null}", "{***"),
                // Partes vacías
                arguments("@ejemplo.es", "***@e***.es"),
                arguments("ana@", "a***@***"),
                // Saltos de línea y caracteres de control: no sobrevive nada del texto añadido
                arguments("ana\n2026-10-06 INFO Login correcto@ejemplo.es", "a***@e***.es"),
                arguments("ana@ejemplo.es\r\n2026-10-06 ERROR falso", "a***@e***"),
                arguments("\nana@ejemplo.es", "_***@e***.es"),
                arguments("ana@ejemplo.es\u2028INFO falso", "a***@e***"));
    }

    @ParameterizedTest(name = "[{0}] -> {1}")
    @MethodSource("correosYSuEnmascarado")
    void enmascarar_correosYTextosConCaracteresDeControl_ocultaDatosYEvitaLogForging( // CP-AUD-06
            String entrada, String esperado) {
        // When
        String enmascarado = EnmascaradorDatosLog.correo(entrada);

        // Then: se conserva solo lo previsto y no hay forma de falsificar una línea de log
        assertThat(enmascarado).isEqualTo(esperado);
        assertThat(contieneCaracteresDeControl(enmascarado)).isFalse();
    }

    @ParameterizedTest(name = "[{0}] -> sin dato")
    @ValueSource(strings = {"", "   ", "\n", "\t \r\n"})
    void enmascarar_correoNuloOEnBlanco_devuelveMarcaDeSinDato( // CP-AUD-06
            String entrada) {
        assertThat(EnmascaradorDatosLog.correo(entrada)).isEqualTo(SIN_DATO);
    }

    @Test
    void enmascarar_correoNulo_devuelveMarcaDeSinDato() { // CP-AUD-06
        assertThat(EnmascaradorDatosLog.correo(null)).isEqualTo(SIN_DATO);
    }

    static Stream<Arguments> textosYSuSaneado() {
        return Stream.of(
                arguments("Mozilla/5.0 (X11; Linux x86_64)", "Mozilla/5.0 (X11; Linux x86_64)"),
                arguments("Mozilla/5.0\nINFO falso", "Mozilla/5.0_INFO falso"),
                arguments("linea1\r\nlinea2", "linea1__linea2"),
                arguments("a\tb", "a_b"),
                arguments("a\u0000b\u001b[31mc", "a_b_[31mc"),
                // Separadores de línea de Unicode
                arguments("x\u2028y\u2029z\u0085w", "x_y_z_w"));
    }

    @ParameterizedTest(name = "[{0}] -> [{1}]")
    @MethodSource("textosYSuSaneado")
    void sanear_textoConCaracteresDeControl_losSustituyePorGuionBajo( // CP-AUD-06
            String entrada, String esperado) {
        // When
        String saneado = EnmascaradorDatosLog.texto(entrada);

        // Then
        assertThat(saneado).isEqualTo(esperado);
        assertThat(contieneCaracteresDeControl(saneado)).isFalse();
    }

    @ParameterizedTest(name = "[{0}] -> (vacio)")
    @ValueSource(strings = {""})
    void sanear_textoVacio_devuelveMarcaDeVacio( // CP-AUD-06
            String entrada) {
        assertThat(EnmascaradorDatosLog.texto(entrada)).isEqualTo("(vacio)");
    }

    @Test
    void sanear_textoNulo_devuelveMarcaDeVacio() { // CP-AUD-06
        assertThat(EnmascaradorDatosLog.texto(null)).isEqualTo("(vacio)");
    }

    private static boolean contieneCaracteresDeControl(String texto) {
        return texto.chars().anyMatch(caracter -> Character.isISOControl(caracter)
                || caracter == 0x2028 || caracter == 0x2029);
    }
}
