package com.esibuy.esibuy_backend.servicio;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/*
 * Limite de intentos de restablecer la contrasena por IP (prefijo esibuy.restablecimiento): maxPorIp intentos en
 * ventanaIp. Se valida al construirlo: una configuracion absurda impide arrancar.
 * Tests: LimitadorRestablecimientoTest (CP-RST-27 y 28) y RestablecimientoConfiguracionTest (CP-RST-29).
 */
@ConfigurationProperties(prefix = "esibuy.restablecimiento")
public record PropiedadesRestablecimiento(int maxPorIp, Duration ventanaIp) {

    public PropiedadesRestablecimiento {
        if (maxPorIp < 1) {
            throw new IllegalArgumentException("esibuy.restablecimiento.max-por-ip debe ser al menos 1");
        }
        if (ventanaIp == null || ventanaIp.isZero() || ventanaIp.isNegative()) {
            throw new IllegalArgumentException("esibuy.restablecimiento.ventana-ip debe ser una duracion positiva");
        }
    }
}
