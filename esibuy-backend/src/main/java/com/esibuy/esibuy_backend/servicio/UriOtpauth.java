package com.esibuy.esibuy_backend.servicio;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/*
 * URI otpauth:// que se dibuja en el QR de activacion, en el formato de Google Authenticator:
 * otpauth://totp/EMISOR:cuenta?secret=BASE32&issuer=EMISOR
 *
 * Solo lleva secret e issuer: la aplicacion ignora algorithm, digits y period. La etiqueta y los parametros se codifican
 * en porcentaje (espacios como %20, nunca '+'), de modo que un correo con caracteres especiales no puede alterar la
 * estructura de la URI. El emisor no puede llevar ':' porque separa emisor y cuenta en la etiqueta.
 * Tests: UriOtpauthTest (CP-2FA-26 a CP-2FA-35).
 */
public final class UriOtpauth {

    private UriOtpauth() {
    }

    public static String construir(String emisor, String cuenta, byte[] secreto) {
        if (emisor == null || emisor.isBlank() || emisor.indexOf(':') >= 0) {
            throw new IllegalArgumentException("El emisor es obligatorio y no puede contener dos puntos");
        }
        if (cuenta == null || cuenta.isBlank()) {
            throw new IllegalArgumentException("La cuenta es obligatoria");
        }
        if (secreto == null || secreto.length == 0) {
            throw new IllegalArgumentException("El secreto es obligatorio");
        }
        String emisorCodificado = codificar(emisor);
        return "otpauth://totp/" + emisorCodificado + ":" + codificar(cuenta)
                + "?secret=" + Base32.codificar(secreto)
                + "&issuer=" + emisorCodificado;
    }

    // URLEncoder codifica los espacios como '+', que en una ruta de URI significaria un '+' literal
    private static String codificar(String texto) {
        return URLEncoder.encode(texto, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
