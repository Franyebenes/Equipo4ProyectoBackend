package com.esibuy.esibuy_backend.servicio;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/*
 * Cifrado del secreto TOTP en reposo con AES-256-GCM. El secreto no puede guardarse con hash porque el servidor necesita
 * el valor original para recalcular el codigo.
 *
 * Formato guardado: "version:base64url(IV de 12 bytes + cifrado + etiqueta de 16 bytes)", sin relleno. La version
 * identifica la clave usada y permite rotarla: se cifra siempre con la activa y se descifra con la que indique el texto.
 * El dato asociado (el id del usuario) queda autenticado: un secreto copiado a otra cuenta no se puede descifrar. Cada
 * llamada usa un Cipher nuevo (no es seguro entre hilos) y un IV aleatorio.
 *
 * Los errores de descifrado son siempre la misma IllegalArgumentException, sin causa y sin datos en el mensaje.
 * Tests: CifradorSecretosTest (CP-2FA-36 a CP-2FA-50).
 */
public final class CifradorSecretos {

    private static final String TRANSFORMACION = "AES/GCM/NoPadding";
    private static final String ALGORITMO_CLAVE = "AES";
    private static final int LONGITUD_CLAVE_BYTES = 32;
    private static final int LONGITUD_IV_BYTES = 12;
    private static final int LONGITUD_ETIQUETA_BITS = 128;
    private static final int LONGITUD_ETIQUETA_BYTES = LONGITUD_ETIQUETA_BITS / 8;
    private static final Pattern VERSION = Pattern.compile("^[0-9]{1,9}$");
    private static final String MENSAJE_ILEGIBLE = "No se pudo descifrar el secreto";

    private final Map<Integer, SecretKeySpec> claves;
    private final int versionActiva;
    private final SecureRandom aleatorio = new SecureRandom();

    public CifradorSecretos(Map<Integer, byte[]> clavesPorVersion, int versionActiva) {
        if (clavesPorVersion == null || clavesPorVersion.isEmpty()) {
            throw new IllegalArgumentException("Hace falta al menos una clave de cifrado");
        }
        Map<Integer, SecretKeySpec> copia = new HashMap<>();
        for (Map.Entry<Integer, byte[]> entrada : clavesPorVersion.entrySet()) {
            Integer version = entrada.getKey();
            byte[] material = entrada.getValue();
            if (version == null || version < 1) {
                throw new IllegalArgumentException("La version de una clave debe ser al menos 1");
            }
            if (material == null || material.length != LONGITUD_CLAVE_BYTES) {
                throw new IllegalArgumentException("Cada clave de cifrado debe medir exactamente 32 bytes");
            }
            // SecretKeySpec copia el array: cambiar el original despues no afecta
            copia.put(version, new SecretKeySpec(material, ALGORITMO_CLAVE));
        }
        if (!copia.containsKey(versionActiva)) {
            throw new IllegalArgumentException("La version activa no esta entre las claves configuradas");
        }
        this.claves = Map.copyOf(copia);
        this.versionActiva = versionActiva;
    }

    public String cifrar(byte[] secreto, String asociado) {
        if (secreto == null || secreto.length == 0) {
            throw new IllegalArgumentException("El secreto a cifrar es obligatorio");
        }
        byte[] datoAsociado = datoAsociado(asociado);
        try {
            byte[] iv = new byte[LONGITUD_IV_BYTES];
            aleatorio.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMACION);
            cipher.init(Cipher.ENCRYPT_MODE, claves.get(versionActiva), new GCMParameterSpec(LONGITUD_ETIQUETA_BITS, iv));
            cipher.updateAAD(datoAsociado);
            byte[] cifrado = cipher.doFinal(secreto);

            byte[] carga = new byte[iv.length + cifrado.length];
            System.arraycopy(iv, 0, carga, 0, iv.length);
            System.arraycopy(cifrado, 0, carga, iv.length, cifrado.length);
            return versionActiva + ":" + Base64.getUrlEncoder().withoutPadding().encodeToString(carga);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo cifrar el secreto");
        }
    }

    public byte[] descifrar(String cifrado, String asociado) {
        byte[] datoAsociado = datoAsociado(asociado);
        int separador = cifrado == null ? -1 : cifrado.indexOf(':');
        if (separador <= 0) {
            throw ilegible();
        }
        String textoVersion = cifrado.substring(0, separador);
        if (!VERSION.matcher(textoVersion).matches()) {
            throw ilegible();
        }
        SecretKeySpec clave = claves.get(Integer.parseInt(textoVersion));
        if (clave == null) {
            throw ilegible();
        }
        byte[] carga = decodificarCarga(cifrado.substring(separador + 1));
        if (carga.length < LONGITUD_IV_BYTES + LONGITUD_ETIQUETA_BYTES + 1) {
            throw ilegible();
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMACION);
            cipher.init(Cipher.DECRYPT_MODE, clave, new GCMParameterSpec(LONGITUD_ETIQUETA_BITS, carga, 0, LONGITUD_IV_BYTES));
            cipher.updateAAD(datoAsociado);
            return cipher.doFinal(carga, LONGITUD_IV_BYTES, carga.length - LONGITUD_IV_BYTES);
        } catch (GeneralSecurityException e) {
            // Etiqueta que no cuadra: manipulado, otro usuario u otra clave. Sin causa para no filtrar nada.
            throw ilegible();
        }
    }

    // True si el texto cifrado se hizo con la clave activa; false si es de una version anterior o no es legible. 
    public boolean usaLaClaveActiva(String cifrado) {
        int separador = cifrado == null ? -1 : cifrado.indexOf(':');
        if (separador <= 0) {
            return false;
        }
        String textoVersion = cifrado.substring(0, separador);
        return VERSION.matcher(textoVersion).matches() && Integer.parseInt(textoVersion) == versionActiva;
    }

    private static byte[] decodificarCarga(String texto) {
        try {
            return Base64.getUrlDecoder().decode(texto);
        } catch (IllegalArgumentException e) {
            throw ilegible();
        }
    }

    private static byte[] datoAsociado(String asociado) {
        if (asociado == null || asociado.isBlank()) {
            throw new IllegalArgumentException("El dato asociado es obligatorio");
        }
        return asociado.getBytes(StandardCharsets.UTF_8);
    }

    private static IllegalArgumentException ilegible() {
        return new IllegalArgumentException(MENSAJE_ILEGIBLE);
    }

    @Override
    public String toString() {
        return "CifradorSecretos[versionActiva=" + versionActiva + ", versiones=" + new TreeSet<>(claves.keySet()) + "]";
    }
}
