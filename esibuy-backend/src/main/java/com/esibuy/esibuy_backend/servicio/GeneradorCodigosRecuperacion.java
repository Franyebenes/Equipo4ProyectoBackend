package com.esibuy.esibuy_backend.servicio;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/*
 * Codigos de recuperacion del 2FA: la salida de emergencia de quien pierde el movil. Cada uno es de un solo uso y tiene
 * 80 bits (16 caracteres Base32); se muestran agrupados de cuatro en cuatro (ABCD-EFGH-IJKL-MNOP).
 *
 * En la base de datos solo se guarda su HMAC-SHA-256 con el pepper: con 80 bits un hash rapido es seguro y comprobar
 * varios codigos no resulta caro, a diferencia de Argon2id. El mensaje lleva un prefijo propio del uso, de modo que el
 * hash nunca coincide con otro HMAC calculado con el mismo pepper (el de las contrasenas, por ejemplo).
 * Tests: GeneradorCodigosRecuperacionTest (CP-2FA-51 a CP-2FA-65).
 */
public class GeneradorCodigosRecuperacion {

    public static final int LONGITUD_CODIGO = 16;
    public static final int CANTIDAD_MINIMA = 1;
    public static final int CANTIDAD_MAXIMA = 20;

    private static final int BYTES_POR_CODIGO = 10;
    private static final int TAMANO_GRUPO = 4;
    private static final String ALGORITMO_HMAC = "HmacSHA256";
    private static final String PREFIJO_HMAC = "esibuy:2fa:codigo-recuperacion:";

    private final SecretKeySpec clavePepper;
    private final SecureRandom aleatorio;

    public GeneradorCodigosRecuperacion(String pepper) {
        this(pepper, new SecureRandom());
    }

    public GeneradorCodigosRecuperacion(String pepper, SecureRandom aleatorio) {
        if (pepper == null || pepper.isBlank()) {
            throw new IllegalStateException(
                    "Falta el pepper: define la propiedad esibuy.seguridad.pepper (ESIBUY_PEPPER)");
        }
        if (aleatorio == null) {
            throw new IllegalArgumentException("La fuente aleatoria es obligatoria");
        }
        this.clavePepper = new SecretKeySpec(pepper.getBytes(StandardCharsets.UTF_8), ALGORITMO_HMAC);
        this.aleatorio = aleatorio;
    }

    // Un codigo nuevo de 16 caracteres Base32 (80 bits). 
    public String nuevoCodigo() {
        byte[] bytes = new byte[BYTES_POR_CODIGO];
        aleatorio.nextBytes(bytes);
        return Base32.codificar(bytes);
    }

    // La cantidad pedida de codigos, todos distintos. 
    public List<String> nuevosCodigos(int cantidad) {
        if (cantidad < CANTIDAD_MINIMA || cantidad > CANTIDAD_MAXIMA) {
            throw new IllegalArgumentException(
                    "La cantidad de codigos debe estar entre " + CANTIDAD_MINIMA + " y " + CANTIDAD_MAXIMA);
        }
        Set<String> codigos = new LinkedHashSet<>();
        while (codigos.size() < cantidad) {
            codigos.add(nuevoCodigo());
        }
        return new ArrayList<>(codigos);
    }

    // ABCD-EFGH-IJKL-MNOP, para mostrarlo al usuario. 
    public static String formatear(String codigo) {
        String canonico = normalizar(codigo);
        if (canonico == null) {
            throw new IllegalArgumentException("No es un codigo de recuperacion");
        }
        StringBuilder resultado = new StringBuilder(LONGITUD_CODIGO + LONGITUD_CODIGO / TAMANO_GRUPO - 1);
        for (int i = 0; i < canonico.length(); i += TAMANO_GRUPO) {
            if (i > 0) {
                resultado.append('-');
            }
            resultado.append(canonico, i, i + TAMANO_GRUPO);
        }
        return resultado.toString();
    }

    /*
     * Forma canonica (16 caracteres en mayusculas) de lo que escribe el usuario: se quitan espacios y guiones y se pasan
     * a mayusculas las letras ASCII. Null si no es un codigo.
     */
    public static String normalizar(String entrada) {
        if (entrada == null) {
            return null;
        }
        StringBuilder canonico = new StringBuilder(LONGITUD_CODIGO);
        for (int i = 0; i < entrada.length(); i++) {
            char c = entrada.charAt(i);
            if (c == ' ' || c == '-') {
                continue;
            }
            // Solo las letras ASCII: toUpperCase cambiaria, por ejemplo, la 'i' sin punto turca por una 'I' latina
            canonico.append(c >= 'a' && c <= 'z' ? (char) (c - ('a' - 'A')) : c);
        }
        if (canonico.length() != LONGITUD_CODIGO) {
            return null;
        }
        for (int i = 0; i < canonico.length(); i++) {
            char c = canonico.charAt(i);
            if (!(c >= 'A' && c <= 'Z') && !(c >= '2' && c <= '7')) {
                return null;
            }
        }
        return canonico.toString();
    }

    // HMAC-SHA-256 en hexadecimal (minusculas) del codigo canonico. 
    public String hash(String codigo) {
        String canonico = normalizar(codigo);
        if (canonico == null) {
            throw new IllegalArgumentException("No es un codigo de recuperacion");
        }
        try {
            // Mac no es thread-safe: una instancia por llamada.
            Mac mac = Mac.getInstance(ALGORITMO_HMAC);
            mac.init(clavePepper);
            return HexFormat.of().formatHex(mac.doFinal((PREFIJO_HMAC + canonico).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo calcular el hash del codigo de recuperacion");
        }
    }

    // True si lo introducido es el codigo del hash guardado. Una entrada mal formada da false, nunca una excepcion.
    public boolean coincide(String introducido, String hashGuardado) {
        if (hashGuardado == null || hashGuardado.isEmpty() || normalizar(introducido) == null) {
            return false;
        }
        return MessageDigest.isEqual(hash(introducido).getBytes(StandardCharsets.UTF_8),
                hashGuardado.getBytes(StandardCharsets.UTF_8));
    }
}
