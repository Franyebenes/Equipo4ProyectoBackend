package com.esibuy.esibuy_backend.seguridad;

import java.time.Duration;
import java.time.Instant;

import com.esibuy.esibuy_backend.modelo.Rol;

import jakarta.servlet.http.HttpSession;

/*
 * Politica de sesion segun el rol. Inactividad maxima: 20 min para CLIENTE y PREMIUM, 15 min para VENDEDOR y ADMIN.
 * Caducidad absoluta, contada desde el inicio de la sesion y aunque haya actividad: 8 h y 6 h respectivamente. Una
 * sesion sin instante de inicio o sin rol se considera caducada (falla cerrado).
 */
public class PoliticaSesion {

    /** Atributo de sesion con el instante (Instant) en que se inicio la sesion. */
    public static final String ATRIBUTO_INICIO = "esibuy.sesion.inicio";
    /** Atributo de sesion con el rol (Rol) del usuario autenticado. */
    public static final String ATRIBUTO_ROL = "esibuy.sesion.rol";

    private static final Duration INACTIVIDAD_CLIENTES = Duration.ofMinutes(20);
    private static final Duration INACTIVIDAD_PERSONAL = Duration.ofMinutes(15);
    private static final Duration ABSOLUTA_CLIENTES = Duration.ofHours(8);
    private static final Duration ABSOLUTA_PERSONAL = Duration.ofHours(6);

    public Duration inactividadMaxima(Rol rol) {
        return switch (rol) {
            case CLIENTE, PREMIUM -> INACTIVIDAD_CLIENTES;
            case VENDEDOR, ADMIN -> INACTIVIDAD_PERSONAL;
        };
    }

    public Duration duracionAbsoluta(Rol rol) {
        return switch (rol) {
            case CLIENTE, PREMIUM -> ABSOLUTA_CLIENTES;
            case VENDEDOR, ADMIN -> ABSOLUTA_PERSONAL;
        };
    }

    /** Caduca justo al cumplirse el limite absoluto del rol, haya habido actividad reciente o no. */
    public boolean haCaducado(HttpSession sesion, Instant ahora) {
        if (!(sesion.getAttribute(ATRIBUTO_INICIO) instanceof Instant inicio)
                || !(sesion.getAttribute(ATRIBUTO_ROL) instanceof Rol rol)) {
            return true;
        }
        return !ahora.isBefore(inicio.plus(duracionAbsoluta(rol)));
    }
}
