package com.esibuy.esibuy_backend.servicio;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

final class NormalizadorRegistro {

    private static final Pattern ESPACIOS = Pattern.compile("\\s+");

    private NormalizadorRegistro() {
    }

    static String texto(String valor) {
    // Texto: sin espacios en los extremos y en NFC. Los espacios internos se mantienen.
        if (valor == null || valor.isBlank()) {
            return null;
        }
        return Normalizer.normalize(valor.strip(), Normalizer.Form.NFC);
    }

    
    static String email(String valor) {
    // Email: sin espacios en los extremos y en minusculas.
        String normalizado = texto(valor);
        return normalizado == null ? null : normalizado.toLowerCase(Locale.ROOT);
    }

    static String contrasena(String valor) {
    // Contrasena: solo NFC. Los espacios forman parte de la contrasena y no se recortan.

        return valor == null ? null : Normalizer.normalize(valor, Normalizer.Form.NFC);
    }

    static String nombreComercial(String valor) {
    // Nombre comercial: como {@link #texto(String)} y ademas con los espacios internos colapsados.
        String normalizado = texto(valor);
        return normalizado == null ? null : ESPACIOS.matcher(normalizado).replaceAll(" ");
    }

    static String claveNombreComercial(String nombreComercialNormalizado) {
        return nombreComercialNormalizado.toLowerCase(Locale.ROOT);
    }
}
