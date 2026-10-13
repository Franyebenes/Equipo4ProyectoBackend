package com.esibuy.esibuy_backend.util;

import com.esibuy.esibuy_backend.servicio.DiccionarioContrasenasProhibidas;

import java.util.Set;

/**
 * Diccionario en memoria para probar ValidadorContrasena sin mocks.
 *
 * Es deliberadamente estricto (distingue mayusculas y formas Unicode): obliga a que el
 * validador normalice a NFC y pase a minusculas la contrasena antes de preguntar si es comun.
 */
public class DiccionarioContrasenasFalso implements DiccionarioContrasenasProhibidas {

    /** En forma NFC (la "n con tilde" va como un unico caracter). */
    public static final String CONTRASENA_FILTRADA = "Contrase\u00f1a-Filtrada-2020";

    private static final Set<String> COMUNES_EN_MINUSCULAS =
            Set.of("12345678", "password1", "qwertyui", "qwertyuiop12");

    private static final Set<String> FILTRADAS = Set.of(CONTRASENA_FILTRADA, "Tr0ub4dor&3-Filtrada");

    @Override
    public boolean esComun(String contrasenaEnMinusculas) {
        return COMUNES_EN_MINUSCULAS.contains(contrasenaEnMinusculas);
    }

    @Override
    public boolean estaFiltrada(String contrasena) {
        return FILTRADAS.contains(contrasena);
    }
}
