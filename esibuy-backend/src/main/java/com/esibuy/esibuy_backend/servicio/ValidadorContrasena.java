package com.esibuy.esibuy_backend.servicio;

import java.text.Normalizer;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.esibuy.esibuy_backend.excepcion.CodigoError;
import com.esibuy.esibuy_backend.util.Constantes;

/**
 * Politica de contrasenas (decisiones D1, D7 y D9). Tests: ValidadorContrasenaTest (CP-PWD-01 a CP-PWD-12).
 *
 * <p>No impone reglas de composicion (mayusculas, digitos, simbolos): solo longitud, diccionarios de
 * contrasenas prohibidas y ausencia de datos personales. Devuelve todos los incumplimientos a la vez y nunca
 * registra ni devuelve la contrasena.
 */
@Component
public class ValidadorContrasena {

    private static final Pattern MARCAS_DIACRITICAS = Pattern.compile("\\p{M}+");
    private static final Pattern SEPARADORES_DE_PALABRAS = Pattern.compile("[^\\p{L}\\p{N}]+");

    private final DiccionarioContrasenasProhibidas diccionario;

    public ValidadorContrasena(DiccionarioContrasenasProhibidas diccionario) {
        this.diccionario = diccionario;
    }

    /** @return conjunto vacio si la contrasena cumple la politica */
    public Set<CodigoError> validar(String contrasena, DatosPersonalesContrasena datosPersonales) {
        Set<CodigoError> errores = EnumSet.noneOf(CodigoError.class);
        if (contrasena == null || contrasena.isBlank()) {
            errores.add(CodigoError.OBLIGATORIO);
            return errores;
        }
        // NFC: la misma contrasena escrita con caracteres compuestos o descompuestos da el mismo resultado
        String enNfc = Normalizer.normalize(contrasena, Normalizer.Form.NFC);
        validarLongitud(enNfc, errores);
        validarDiccionarios(enNfc, errores);
        if (contieneDatosPersonales(enNfc, datosPersonales)) {
            errores.add(CodigoError.CONTRASENA_CON_DATOS_PERSONALES);
        }
        return errores;
    }

    /** Cuenta caracteres reales (puntos de codigo), no unidades UTF-16: un emoji cuenta como 1 (CP-PWD-12). */
    private static void validarLongitud(String contrasena, Set<CodigoError> errores) {
        int longitud = contrasena.codePointCount(0, contrasena.length());
        if (longitud < Constantes.LONGITUD_MINIMA) {
            errores.add(CodigoError.CONTRASENA_CORTA);
        } else if (longitud > Constantes.LONGITUD_MAXIMA) {
            errores.add(CodigoError.CONTRASENA_LARGA);
        }
    }

    /** Las comunes se comparan sin distinguir mayusculas; las filtradas, tal cual. */
    private void validarDiccionarios(String contrasena, Set<CodigoError> errores) {
        if (diccionario.esComun(contrasena.toLowerCase(Locale.ROOT))) {
            errores.add(CodigoError.CONTRASENA_COMUN);
        }
        if (diccionario.estaFiltrada(contrasena)) {
            errores.add(CodigoError.CONTRASENA_FILTRADA);
        }
    }

    private static boolean contieneDatosPersonales(String contrasena, DatosPersonalesContrasena datos) {
        String contrasenaComparable = sinAcentosEnMinusculas(contrasena);
        return palabrasProhibidas(datos).stream().anyMatch(contrasenaComparable::contains);
    }

    /** Palabras de los datos personales, y el nombre de la aplicacion, que no pueden aparecer en la contrasena. */
    private static Set<String> palabrasProhibidas(DatosPersonalesContrasena datos) {
        Set<String> palabras = new LinkedHashSet<>();
        palabras.add(Constantes.NOMBRE_APLICACION);
        if (datos == null) {
            return palabras;
        }
        anadirPalabras(palabras, datos.nombre());
        anadirPalabras(palabras, datos.apellidos());
        anadirPalabras(palabras, datos.nombreComercial());

        String email = datos.email();
        if (email != null && !email.isBlank()) {
            palabras.add(sinAcentosEnMinusculas(email.strip()));
            // Solo la parte local: el dominio ("gmail", "com", "es"...) no es un dato personal del usuario
            int arroba = email.indexOf('@');
            anadirPalabras(palabras, arroba >= 0 ? email.substring(0, arroba) : email);
        }
        return palabras;
    }

    /** Anade las palabras del texto con al menos LONGITUD_MINIMA_DATO_PERSONAL letras (CP-PWD-09). */
    private static void anadirPalabras(Set<String> palabras, String texto) {
        if (texto == null) {
            return;
        }
        for (String palabra : SEPARADORES_DE_PALABRAS.split(sinAcentosEnMinusculas(texto))) {
            if (palabra.codePointCount(0, palabra.length()) >= Constantes.LONGITUD_MINIMA_DATO_PERSONAL) {
                palabras.add(palabra);
            }
        }
    }

    /** Forma de comparacion: sin acentos ni otras marcas diacriticas y en minusculas. */
    private static String sinAcentosEnMinusculas(String texto) {
        String descompuesto = Normalizer.normalize(texto, Normalizer.Form.NFD);
        return MARCAS_DIACRITICAS.matcher(descompuesto).replaceAll("").toLowerCase(Locale.ROOT);
    }
}
