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
 * contrasenas prohibidas y ausencia de datos personales. Nunca registra ni devuelve la contrasena.
 */
@Component
public class ValidadorContrasena {

    private static final Pattern MARCAS_DIACRITICAS = Pattern.compile("\\p{M}+");
    private static final Pattern SEPARADORES_DE_PALABRAS = Pattern.compile("[^\\p{L}\\p{N}]+");

    private final DiccionarioContrasenasProhibidas diccionario;

    public ValidadorContrasena(DiccionarioContrasenasProhibidas diccionario) {
        this.diccionario = diccionario;
    }

    public Set<CodigoError> validar(String contrasena, DatosPersonalesContrasena datosPersonales) {
        //no sé si igual es mejor dividirlo en varios métodos
        Set<CodigoError> errores = EnumSet.noneOf(CodigoError.class);
        if (contrasena == null || contrasena.isBlank()) {
            errores.add(CodigoError.OBLIGATORIO);
        }else{

            String contrasenaNorm = Normalizer.normalize(contrasena, Normalizer.Form.NFC);

            int longitud = contrasenaNorm.codePointCount(0, contrasenaNorm.length());
            if (longitud < Constantes.LONGITUD_MINIMA) {
                errores.add(CodigoError.CONTRASENA_CORTA);
            } else if (longitud > Constantes.LONGITUD_MAXIMA) {
                errores.add(CodigoError.CONTRASENA_LARGA);
            }
            if (diccionario.esComun(contrasenaNorm.toLowerCase(Locale.ROOT))) {
                errores.add(CodigoError.CONTRASENA_COMUN);
            }
            if (diccionario.estaFiltrada(contrasenaNorm)) {
                errores.add(CodigoError.CONTRASENA_FILTRADA);
            }
            if (contieneDatosPersonales(contrasenaNorm, datosPersonales)) {
                errores.add(CodigoError.CONTRASENA_CON_DATOS_PERSONALES);
            }
        }
        return errores;
    }

    private static boolean contieneDatosPersonales(String contrasena, DatosPersonalesContrasena datos) {
        String contrasenaComparable = normalizarString(contrasena);
        return palabrasProhibidas(datos).stream().anyMatch(contrasenaComparable::contains);
    }

    private static Set<String> palabrasProhibidas(DatosPersonalesContrasena datos) {
        //método para obtener las palabras que no pueden estar en la pwd
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
            palabras.add(normalizarString(email.strip()));
            int arroba = email.indexOf('@');
            String parteLocal = arroba >= 0 ? email.substring(0, arroba) : email;
            anadirPalabras(palabras, parteLocal);
            // Solo la parte local: el dominio ("gmail", "com", "es"...) no es un dato personal del usuario.
        }
        return palabras;
    }

    private static void anadirPalabras(Set<String> palabras, String texto) {
        if (texto == null) {
            return;
        }
        for (String palabra : SEPARADORES_DE_PALABRAS.split(normalizarString(texto))) {
            if (palabra.codePointCount(0, palabra.length()) >= Constantes.LONGITUD_MINIMA_DATO_PERSONAL) {
                palabras.add(palabra);
            }
        }
    }

    private static String normalizarString(String texto) {
        String descompuesto = Normalizer.normalize(texto, Normalizer.Form.NFD);
        return MARCAS_DIACRITICAS.matcher(descompuesto).replaceAll("").toLowerCase(Locale.ROOT);
    }
}
