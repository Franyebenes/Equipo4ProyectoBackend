package com.esibuy.esibuy_backend.servicio;

import java.time.LocalDate;
import java.time.Period;
import java.util.regex.Pattern;

import org.bson.types.ObjectId;

import com.esibuy.esibuy_backend.excepcion.CodigoError;

/**
 * Reglas de formato de los campos del registro. Son locales (no consultan la BBDD ni servicios externos) y reciben
 * los valores ya normalizados por {@link Normalizador}.
 */
final class ReglasCampos {

    static final int EDAD_MINIMA = 18;
    /** Por encima de esta edad la fecha se considera un error al introducirla (CP-REG-35). */
    static final int EDAD_MAXIMA = 120;
    /** Longitud maxima de una direccion de email (RFC 5321). */
    static final int LONGITUD_MAXIMA_EMAIL = 254;

    /** Caracteres que no tienen sentido en nombres y que se usan en XSS y en operadores NoSQL (CP-SEG-04/05). */
    private static final Pattern CARACTERES_PROHIBIDOS = Pattern.compile("[<>{}$]");
    /** Formato basico de email ya en minusculas: parte local, arroba unica y dominio con TLD. */
    private static final Pattern FORMATO_EMAIL =
            Pattern.compile("^[a-z0-9._%+-]+@(?:[a-z0-9]++(?:-++[a-z0-9]++)*+\\.)++[a-z]{2,}$");
    private static final Pattern FORMATO_TELEFONO = Pattern.compile("^[0-9]{9}$");

    private ReglasCampos() {
    }

    static void validarTextoObligatorio(String campo, String valor, int longitudMaxima, ErroresValidacion errores) {
        if (valor == null) {
            errores.anadir(campo, CodigoError.OBLIGATORIO);
            return;
        }
        if (valor.codePointCount(0, valor.length()) > longitudMaxima) {
            errores.anadir(campo, CodigoError.LONGITUD_EXCESIVA);
        }
        if (CARACTERES_PROHIBIDOS.matcher(valor).find()) {
            errores.anadir(campo, CodigoError.FORMATO_INVALIDO);
        }
    }

    static void validarEmail(String campo, String email, ErroresValidacion errores) {
        if (email == null) {
            errores.anadir(campo, CodigoError.OBLIGATORIO);
        } else if (email.length() > LONGITUD_MAXIMA_EMAIL) {
            errores.anadir(campo, CodigoError.LONGITUD_EXCESIVA);
        } else if (!FORMATO_EMAIL.matcher(email).matches()) {
            errores.anadir(campo, CodigoError.FORMATO_INVALIDO);
        }
    }

    /** El telefono es opcional; si se informa, deben ser exactamente 9 digitos (CP-REG-26). */
    static void validarTelefono(String campo, String telefono, ErroresValidacion errores) {
        if (telefono != null && !FORMATO_TELEFONO.matcher(telefono).matches()) {
            errores.anadir(campo, CodigoError.TELEFONO_INVALIDO);
        }
    }

    /** La categoria debe ser un ObjectId bien formado; su existencia se comprueba despues (CP-REG-45). */
    static void validarCategoriaPrincipal(String campo, String categoriaId, ErroresValidacion errores) {
        if (categoriaId == null) {
            errores.anadir(campo, CodigoError.OBLIGATORIO);
        } else if (!ObjectId.isValid(categoriaId)) {
            errores.anadir(campo, CodigoError.FORMATO_INVALIDO);
        }
    }

    static void validarFechaNacimiento(String campo, LocalDate fechaNacimiento, LocalDate hoy,
                                       ErroresValidacion errores) {
        if (fechaNacimiento == null) {
            errores.anadir(campo, CodigoError.OBLIGATORIO);
        } else if (fechaNacimiento.isAfter(hoy) || fechaNacimiento.isBefore(hoy.minusYears(EDAD_MAXIMA))) {
            errores.anadir(campo, CodigoError.FECHA_INVALIDA);
        } else if (Period.between(fechaNacimiento, hoy).getYears() < EDAD_MINIMA) {
            errores.anadir(campo, CodigoError.MENOR_DE_EDAD);
        }
    }

    static void validarTextoOpcional(String campo, String valor, int longitudMaxima, ErroresRegistro errores) {
        if (valor != null) {
            if (valor.codePointCount(0, valor.length()) > longitudMaxima) {
                errores.anadir(campo, CodigoError.LONGITUD_EXCESIVA);
            }
            if (CARACTERES_PROHIBIDOS.matcher(valor).find()) {
                errores.anadir(campo, CodigoError.FORMATO_INVALIDO);
            }
        }
    }
}
