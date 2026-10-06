package com.esibuy.esibuy_backend.servicio;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Normalizacion de las entradas del registro. Los valores ausentes o en blanco se devuelven como null para que
 * los campos opcionales no lleguen al documento.
 */
final class NormalizadorRegistro {

    private static final Pattern ESPACIOS = Pattern.compile("\\s+");

    private NormalizadorRegistro() {
    }

    /** Nombres, apellidos, DNI...: sin espacios en los extremos y en NFC. Los espacios internos se mantienen. */
    static String texto(String valor) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        return Normalizer.normalize(valor.strip(), Normalizer.Form.NFC);
    }

    /** Email: sin espacios en los extremos y en minusculas. */
    static String email(String valor) {
        String normalizado = texto(valor);
        return normalizado == null ? null : normalizado.toLowerCase(Locale.ROOT);
    }

    /** Contrasena: solo NFC. Los espacios forman parte de la contrasena y no se recortan. */
    static String contrasena(String valor) {
        return valor == null ? null : Normalizer.normalize(valor, Normalizer.Form.NFC);
    }

    /** Nombre comercial: como {@link #texto(String)} y ademas con los espacios internos colapsados. */
    static String nombreComercial(String valor) {
        String normalizado = texto(valor);
        return normalizado == null ? null : ESPACIOS.matcher(normalizado).replaceAll(" ");
    }

    /** Clave de unicidad del nombre comercial ya normalizado: en minusculas (CP-REG-43). */
    static String claveNombreComercial(String nombreComercialNormalizado) {
        return nombreComercialNormalizado.toLowerCase(Locale.ROOT);
    }
}
