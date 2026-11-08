package com.esibuy.esibuy_backend.util;

import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

import com.esibuy.esibuy_backend.servicio.PropiedadesDosFactores;

/**
 * Constructor de PropiedadesDosFactores para los tests. Parte de los valores acordados (clave 1 activa, activacion de 10
 * minutos, desafio de 5, 5 intentos por desafio, 10 fallos por cuenta en 15 minutos, 30 por IP en una hora y 10 codigos de
 * recuperacion) y cada test cambia solo lo que quiere probar.
 */
public final class PropiedadesDosFactoresPrueba {

    /** La clave de cifrado de pruebas, para construir un CifradorSecretos que descifre lo mismo que el de los tests. */
    public static final byte[] CLAVE = claveDeSemilla(7);

    private PropiedadesDosFactoresPrueba() {
    }

    public static byte[] claveDeSemilla(int semilla) {
        byte[] clave = new byte[32];
        Arrays.fill(clave, (byte) semilla);
        return clave;
    }

    /** Los valores acordados. */
    public static PropiedadesDosFactores acordadas() {
        return con(10, 30, 10);
    }

    /** Los valores acordados salvo los limites de fallos por cuenta y por IP. */
    public static PropiedadesDosFactores conLimites(int maxFallosCuenta, int maxPorIp) {
        return con(maxFallosCuenta, maxPorIp, 10);
    }

    /** Los valores acordados salvo los limites y el numero de codigos de recuperacion. */
    public static PropiedadesDosFactores con(int maxFallosCuenta, int maxPorIp, int codigosRecuperacion) {
        return new PropiedadesDosFactores("ESIBuy", Map.of(1, Base64.getEncoder().encodeToString(CLAVE)), 1,
                Duration.ofMinutes(10), Duration.ofMinutes(5), 5, maxFallosCuenta, Duration.ofMinutes(15), maxPorIp,
                Duration.ofHours(1), codigosRecuperacion);
    }
}
