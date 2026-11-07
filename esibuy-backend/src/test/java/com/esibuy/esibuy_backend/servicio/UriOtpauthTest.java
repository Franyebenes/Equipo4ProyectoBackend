package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * URI otpauth:// que se dibuja en el QR (HdU 2FA, fase 0). Casos: CP-2FA-26 a CP-2FA-35.
 *
 * Formato de Google Authenticator (Key URI Format):
 * otpauth://totp/EMISOR:cuenta?secret=BASE32&issuer=EMISOR. Solo lleva secret e issuer: la aplicacion ignora algorithm,
 * digits y period, asi que incluirlos no aporta nada y puede confundir a otras aplicaciones. La etiqueta y los
 * parametros se codifican en porcentaje (los espacios como %20, nunca como '+'), de modo que un correo con caracteres
 * especiales no pueda alterar la estructura de la URI.
 *
 * Terminada: ya no lleva la etiqueta pendiente-2fa y entra en la regresion.
 */
class UriOtpauthTest {

    private static final byte[] SECRETO = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
    // Base32 de ese secreto
    private static final String SECRETO_BASE32 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @Test
    void construir_formatoDeGoogleAuthenticator() { // CP-2FA-26
        String uri = UriOtpauth.construir("ESIBuy", "lucia@email.es", SECRETO);

        assertThat(uri).isEqualTo("otpauth://totp/ESIBuy:lucia%40email.es?secret=" + SECRETO_BASE32
                + "&issuer=ESIBuy");
    }

    @Test
    void construir_elSecretoVaEnBase32SinRelleno() { // CP-2FA-27
        byte[] secretoConRelleno = "123456789012345678".getBytes(StandardCharsets.US_ASCII); // 18 bytes: no es multiplo de 5

        String uri = UriOtpauth.construir("ESIBuy", "lucia@email.es", secretoConRelleno);

        assertThat(uri).contains("secret=" + Base32.codificar(secretoConRelleno)).doesNotContain("=&").doesNotContain("%3D");
    }

    @Test
    void construir_noIncluyeAlgoritmoDigitosNiPeriodo() { // CP-2FA-28
        String uri = UriOtpauth.construir("ESIBuy", "lucia@email.es", SECRETO);

        assertThat(uri).doesNotContain("algorithm").doesNotContain("digits").doesNotContain("period");
    }

    @Test
    void construir_elEmisorApareceEnLaEtiquetaYEnElParametro() { // CP-2FA-29
        String uri = UriOtpauth.construir("ESIBuy", "lucia@email.es", SECRETO);

        assertThat(uri).contains("/ESIBuy:").endsWith("&issuer=ESIBuy");
    }

    @Test
    void construir_losEspaciosSeCodificanComoPorcentaje20() { // CP-2FA-30
        String uri = UriOtpauth.construir("ESI Buy", "lucia perez@email.es", SECRETO);

        assertThat(uri).contains("/ESI%20Buy:lucia%20perez%40email.es?").endsWith("&issuer=ESI%20Buy")
                .doesNotContain("+").doesNotContain(" ");
    }

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource(delimiter = '|', value = {
            "a&b|a%26b",
            "a=b|a%3Db",
            "a?b|a%3Fb",
            "a#b|a%23b",
            "a%b|a%25b",
            "a/b|a%2Fb",
            "a:b|a%3Ab",
            "a+b|a%2Bb",
            "José|Jos%C3%A9" })
    void construir_loscaracteresReservadosDeLaCuentaSeCodifican(String cuenta, String codificada) { // CP-2FA-31
        String uri = UriOtpauth.construir("ESIBuy", cuenta, SECRETO);

        assertThat(uri).contains("/ESIBuy:" + codificada + "?secret=");
    }

    @Test
    void construir_loscaracteresReservadosDelEmisorSeCodifican() { // CP-2FA-31
        String uri = UriOtpauth.construir("ESI&Buy#1", "lucia@email.es", SECRETO);

        assertThat(uri).contains("/ESI%26Buy%231:").endsWith("&issuer=ESI%26Buy%231");
    }

    @Test
    void construir_unaCuentaConSaltosDeLineaOParametrosNoAlteraLaEstructura() { // CP-2FA-32
        String uri = UriOtpauth.construir("ESIBuy", "x@y.es\n&secret=AAAA&issuer=Falso", SECRETO);

        assertThat(uri).doesNotContain("\n").doesNotContain("\r");
        // Solo hay un secret y un issuer: el atacante no ha podido anadir otros
        assertThat(uri.split("secret=", -1)).hasSize(2);
        assertThat(uri.split("issuer=", -1)).hasSize(2);
        assertThat(uri).endsWith("&issuer=ESIBuy");
    }

    @Test
    void construir_elResultadoEsUnaUriValidaYSeDescomponeComoSeEsperaPorElLector() { // CP-2FA-33
        String uri = UriOtpauth.construir("ESI Buy", "José Pérez@email.es", SECRETO);

        URI analizada = URI.create(uri);

        assertThat(analizada.getScheme()).isEqualTo("otpauth");
        assertThat(analizada.getHost()).isEqualTo("totp");
        assertThat(analizada.getPath()).isEqualTo("/ESI Buy:José Pérez@email.es");
        assertThat(analizada.getQuery()).isEqualTo("secret=" + SECRETO_BASE32 + "&issuer=ESI Buy");
    }

    @Test
    void construir_emisorConDosPuntos_lanzaExcepcion() { // CP-2FA-34
        // Los dos puntos separan emisor y cuenta en la etiqueta: uno dentro del emisor la haria ambigua
        assertThrows(IllegalArgumentException.class, () -> UriOtpauth.construir("ESI:Buy", "lucia@email.es", SECRETO));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { " ", "   " })
    void construir_emisorOCuentaVaciosONulos_lanzaExcepcion(String valor) { // CP-2FA-35
        assertThrows(IllegalArgumentException.class, () -> UriOtpauth.construir(valor, "lucia@email.es", SECRETO));
        assertThrows(IllegalArgumentException.class, () -> UriOtpauth.construir("ESIBuy", valor, SECRETO));
    }

    @Test
    void construir_secretoNuloOVacio_lanzaExcepcion() { // CP-2FA-35
        assertThrows(IllegalArgumentException.class, () -> UriOtpauth.construir("ESIBuy", "lucia@email.es", null));
        assertThrows(IllegalArgumentException.class,
                () -> UriOtpauth.construir("ESIBuy", "lucia@email.es", new byte[0]));
    }
}
