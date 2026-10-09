package com.esibuy.esibuy_backend.servicio;

import java.util.regex.Pattern;

/*
 * Enmascarado y saneado de datos antes de escribirlos en el log.
 *
 * <p>El correo nunca se escribe completo: solo el primer caracter de la parte local, el primero del dominio y la
 * terminacion (.es, .com) si es puramente alfabetica. Los textos que controla el cliente (user agent, IP) se sanean:
 * cada caracter de control o separador de linea pasa a ser un guion bajo, de modo que no se pueda falsificar una
 * linea de log.
 */
final class EnmascaradorDatosLog {

    private static final String SIN_CORREO = "(sin correo)";
    private static final String VACIO = "(vacio)";
    private static final String MASCARA = "***";
    private static final Pattern TERMINACION_DE_DOMINIO = Pattern.compile("^[A-Za-z]{2,24}$");

    private EnmascaradorDatosLog() {
    }

    static String correo(String correo) {
        if (correo == null || correo.isBlank()) {
            return SIN_CORREO;
        }
        int arroba = correo.lastIndexOf('@');
        if (arroba < 0) {
            return primerCaracter(correo) + MASCARA;
        }
        return primerCaracter(correo.substring(0, arroba)) + MASCARA + "@"
                + dominioEnmascarado(correo.substring(arroba + 1));
    }

    static String texto(String texto) {
        if (texto == null || texto.isEmpty()) {
            return VACIO;
        }
        StringBuilder saneado = new StringBuilder(texto.length());
        texto.codePoints().forEach(caracter -> saneado.appendCodePoint(esDeControl(caracter) ? '_' : caracter));
        return saneado.toString();
    }

    private static String dominioEnmascarado(String dominio) {
        String enmascarado = primerCaracter(dominio) + MASCARA;
        String terminacion = dominio.substring(dominio.lastIndexOf('.') + 1);
        boolean tieneTerminacion = dominio.indexOf('.') >= 0 && TERMINACION_DE_DOMINIO.matcher(terminacion).matches();
        return tieneTerminacion ? enmascarado + "." + terminacion : enmascarado;
    }

    private static String primerCaracter(String texto) {
        return texto.isEmpty() ? "" : texto(texto.substring(0, texto.offsetByCodePoints(0, 1)));
    }

    private static boolean esDeControl(int caracter) {
    return Character.isISOControl(caracter) || caracter == 0x2028 || caracter == 0x2029;
}
}
