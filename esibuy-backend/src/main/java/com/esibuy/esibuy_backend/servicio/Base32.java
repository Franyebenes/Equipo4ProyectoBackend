package com.esibuy.esibuy_backend.servicio;

/*
 * Base32 del RFC 4648 sin relleno, que es el formato en el que las aplicaciones autenticadoras reciben el secreto TOTP
 * (alfabeto A-Z y 2-7). Se usa tambien para los codigos de recuperacion.
 *
 * La decodificacion es estricta para que un mismo secreto tenga una sola representacion: no admite relleno '=', espacios
 * ni otros simbolos, rechaza las longitudes imposibles y los bits sobrantes distintos de cero. Acepta minusculas.
 * Tests: Base32Test (CP-2FA-01 a CP-2FA-07).
 */
public final class Base32 {

    private static final String ALFABETO = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int BITS_POR_CARACTER = 5;
    private static final int BITS_POR_BYTE = 8;

    private Base32() {
    }

    public static String codificar(byte[] datos) {
        if (datos == null) {
            throw new IllegalArgumentException("Los datos a codificar son obligatorios");
        }
        StringBuilder resultado = new StringBuilder((datos.length * BITS_POR_BYTE + BITS_POR_CARACTER - 1) / BITS_POR_CARACTER);
        int acumulador = 0;
        int bits = 0;
        for (byte dato : datos) {
            acumulador = (acumulador << BITS_POR_BYTE) | (dato & 0xFF);
            bits += BITS_POR_BYTE;
            while (bits >= BITS_POR_CARACTER) {
                resultado.append(ALFABETO.charAt((acumulador >> (bits - BITS_POR_CARACTER)) & 0x1F));
                bits -= BITS_POR_CARACTER;
            }
            // Solo hacen falta los bits pendientes; el resto se descarta para que no desborde
            acumulador &= (1 << bits) - 1;
        }
        if (bits > 0) {
            resultado.append(ALFABETO.charAt((acumulador << (BITS_POR_CARACTER - bits)) & 0x1F));
        }
        return resultado.toString();
    }

    public static byte[] decodificar(String texto) {
        if (texto == null) {
            throw new IllegalArgumentException("El texto a decodificar es obligatorio");
        }
        byte[] resultado = new byte[texto.length() * BITS_POR_CARACTER / BITS_POR_BYTE];
        int escritos = 0;
        int acumulador = 0;
        int bits = 0;
        for (int i = 0; i < texto.length(); i++) {
            int valor = valorDe(texto.charAt(i));
            acumulador = (acumulador << BITS_POR_CARACTER) | valor;
            bits += BITS_POR_CARACTER;
            if (bits >= BITS_POR_BYTE) {
                resultado[escritos++] = (byte) (acumulador >> (bits - BITS_POR_BYTE));
                bits -= BITS_POR_BYTE;
                acumulador &= (1 << bits) - 1;
            }
        }
        // Sobran como mucho 4 bits en una longitud valida, y deben ser cero
        if (bits >= BITS_POR_CARACTER || acumulador != 0) {
            throw new IllegalArgumentException("Base32 no valido");
        }
        return resultado;
    }

    private static int valorDe(char caracter) {
        char mayuscula = caracter >= 'a' && caracter <= 'z' ? (char) (caracter - ('a' - 'A')) : caracter;
        int valor = ALFABETO.indexOf(mayuscula);
        if (valor < 0) {
            throw new IllegalArgumentException("Base32 no valido");
        }
        return valor;
    }
}
