package com.esibuy.esibuy_backend.servicio;

import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;

import org.springframework.boot.context.properties.ConfigurationProperties;

/*
 * Configuracion del segundo factor (prefijo esibuy.dosfactores). Se valida al construirla: una configuracion absurda
 * impide arrancar en lugar de dejar el 2FA inutilizable o sin proteccion.
 * Tests: PropiedadesDosFactoresTest (CP-2FA-66 a CP-2FA-78) y ConfiguracionDosFactoresTest (CP-2FA-79 a CP-2FA-88).
 *
 * emisor: nombre que muestra la aplicacion autenticadora (sin ':'); claves: clave AES-256 de cifrado de los secretos por
 * version, en Base64 y de 32 bytes; versionActiva: la que cifra; caducidadActivacion: cuanto se guarda un secreto sin
 * confirmar; caducidadDesafio: cuanto dura el paso intermedio del login; maxIntentosDesafio: intentos por desafio;
 * maxFallosCuenta y ventanaCuenta: fallos por cuenta en su ventana deslizante; maxPorIp y ventanaIp: intentos por IP;
 * codigosRecuperacion: cuantos codigos se generan.
 */
@ConfigurationProperties(prefix = "esibuy.dosfactores")
public record PropiedadesDosFactores(
        String emisor,
        Map<Integer, String> claves,
        int versionActiva,
        Duration caducidadActivacion,
        Duration caducidadDesafio,
        int maxIntentosDesafio,
        int maxFallosCuenta,
        Duration ventanaCuenta,
        int maxPorIp,
        Duration ventanaIp,
        int codigosRecuperacion) {

    private static final int LONGITUD_MAXIMA_EMISOR = 40;
    private static final int LONGITUD_CLAVE_BYTES = 32;
    private static final Duration LIMITE_CADUCIDAD = Duration.ofHours(1);
    private static final String PREFIJO = "esibuy.dosfactores.";
    private static final String PREFIJO_CLAVES = PREFIJO + "claves.";

    public PropiedadesDosFactores {
        comprobarEmisor(emisor);
        comprobarClaves(claves);
        if (!claves.containsKey(versionActiva)) {
            throw new IllegalArgumentException(PREFIJO + "version-activa no esta entre las claves configuradas");
        }
        comprobarPositiva(caducidadActivacion, "caducidad-activacion");
        comprobarPositiva(caducidadDesafio, "caducidad-desafio");
        comprobarPositiva(ventanaCuenta, "ventana-cuenta");
        comprobarPositiva(ventanaIp, "ventana-ip");
        if (caducidadActivacion.compareTo(LIMITE_CADUCIDAD) > 0) {
            throw new IllegalArgumentException(PREFIJO + "caducidad-activacion no puede superar una hora");
        }
        if (caducidadDesafio.compareTo(LIMITE_CADUCIDAD) > 0) {
            throw new IllegalArgumentException(PREFIJO + "caducidad-desafio no puede superar una hora");
        }
        comprobarAlMenosUno(maxIntentosDesafio, "max-intentos-desafio");
        comprobarAlMenosUno(maxFallosCuenta, "max-fallos-cuenta");
        comprobarAlMenosUno(maxPorIp, "max-por-ip");
        if (codigosRecuperacion < GeneradorCodigosRecuperacion.CANTIDAD_MINIMA
                || codigosRecuperacion > GeneradorCodigosRecuperacion.CANTIDAD_MAXIMA) {
            throw new IllegalArgumentException(PREFIJO + "codigos-recuperacion debe estar entre "
                    + GeneradorCodigosRecuperacion.CANTIDAD_MINIMA + " y "
                    + GeneradorCodigosRecuperacion.CANTIDAD_MAXIMA);
        }
        // Copia inmutable: cambiar el mapa original despues no afecta
        claves = Map.copyOf(claves);
    }

    //Las claves decodificadas. Cada llamada devuelve arrays nuevos: cambiarlos no altera la configuracion.
    public Map<Integer, byte[]> clavesEnBytes() {
        Map<Integer, byte[]> resultado = new HashMap<>();
        claves.forEach((version, base64) -> resultado.put(version, Base64.getDecoder().decode(base64)));
        return resultado;
    }

    private static void comprobarEmisor(String emisor) {
        if (emisor == null || emisor.isBlank()) {
            throw new IllegalArgumentException(PREFIJO + "emisor es obligatorio");
        }
        if (emisor.length() > LONGITUD_MAXIMA_EMISOR) {
            throw new IllegalArgumentException(PREFIJO + "emisor no puede superar " + LONGITUD_MAXIMA_EMISOR + " caracteres");
        }
        if (emisor.indexOf(':') >= 0 || emisor.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(PREFIJO + "emisor no puede llevar dos puntos ni caracteres de control");
        }
    }

    private static void comprobarClaves(Map<Integer, String> claves) {
        if (claves == null || claves.isEmpty()) {
            throw new IllegalArgumentException(PREFIJO + "claves necesita al menos una clave de cifrado");
        }
        for (Map.Entry<Integer, String> entrada : claves.entrySet()) {
            if (entrada.getKey() == null || entrada.getKey() < 1) {
                throw new IllegalArgumentException(PREFIJO + "claves: la version debe ser al menos 1");
            }
            String clave = entrada.getValue();
            if (clave == null || clave.isBlank()) {
                throw new IllegalArgumentException(PREFIJO_CLAVES + entrada.getKey() + " esta vacia");
            }
            try {
                if (Base64.getDecoder().decode(clave).length != LONGITUD_CLAVE_BYTES) {
                    throw new IllegalArgumentException(PREFIJO_CLAVES + entrada.getKey() + " debe medir 32 bytes");
                }
            } catch (IllegalArgumentException e) {
                // Ni el mensaje ni la causa llevan el valor de la clave
                throw new IllegalArgumentException(
                        PREFIJO_CLAVES + entrada.getKey() + " debe ser una clave de 32 bytes en Base64");
            }
        }
    }

    private static void comprobarPositiva(Duration duracion, String nombre) {
        if (duracion == null || duracion.isZero() || duracion.isNegative()) {
            throw new IllegalArgumentException(PREFIJO + nombre + " debe ser una duracion positiva");
        }
    }

    private static void comprobarAlMenosUno(int valor, String nombre) {
        if (valor < 1) {
            throw new IllegalArgumentException(PREFIJO + nombre + " debe ser al menos 1");
        }
    }

    // Sin las claves: un registro de arranque o una traza no debe poder filtrarlas
    @Override
    public String toString() {
        return "PropiedadesDosFactores[emisor=" + emisor + ", versionActiva=" + versionActiva
                + ", versiones=" + new TreeSet<>(claves.keySet()) + "]";
    }
}
