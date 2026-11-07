package com.esibuy.esibuy_backend.servicio;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/*
 * Limites de la solicitud de recuperacion de contrasena (prefijo esibuy.recuperacion). Se validan al construirlas: una
 * configuracion absurda impide arrancar en lugar de dejar la recuperacion inutilizable o sin proteccion.
 * Tests: PropiedadesRecuperacionTest (CP-LIM-01 a CP-LIM-05) y LimitesRecuperacionConfiguracionTest (CP-LIM-06 a 09).
 *
 * maxPorCuenta: solicitudes por cuenta en la ventana diaria; ventanaCuenta: duracion de esa ventana;
 * cooldown: espera minima entre dos solicitudes de la misma cuenta (menor que la ventana);
 * maxPorIp y ventanaIp: solicitudes por IP en su ventana.
 */
@ConfigurationProperties(prefix = "esibuy.recuperacion")
public record PropiedadesRecuperacion(
        int maxPorCuenta,
        Duration ventanaCuenta,
        Duration cooldown,
        int maxPorIp,
        Duration ventanaIp) {

    public PropiedadesRecuperacion {
        if (maxPorCuenta < 1) {
            throw new IllegalArgumentException("esibuy.recuperacion.max-por-cuenta debe ser al menos 1");
        }
        if (maxPorIp < 1) {
            throw new IllegalArgumentException("esibuy.recuperacion.max-por-ip debe ser al menos 1");
        }
        comprobarPositiva(ventanaCuenta, "esibuy.recuperacion.ventana-cuenta");
        comprobarPositiva(cooldown, "esibuy.recuperacion.cooldown");
        comprobarPositiva(ventanaIp, "esibuy.recuperacion.ventana-ip");
        if (cooldown.compareTo(ventanaCuenta) >= 0) {
            throw new IllegalArgumentException(
                    "esibuy.recuperacion.cooldown debe ser menor que esibuy.recuperacion.ventana-cuenta");
        }
    }

    private static void comprobarPositiva(Duration duracion, String nombre) {
        if (duracion == null || duracion.isZero() || duracion.isNegative()) {
            throw new IllegalArgumentException(nombre + " debe ser una duracion positiva");
        }
    }
}
