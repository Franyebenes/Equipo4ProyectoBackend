package com.esibuy.esibuy_backend.seguridad;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Anade un pepper secreto a la contrasena antes de delegar en el codificador real (Argon2id).
 * Tests: CodificadorContrasenaTest (CP-REG-49 y CP-REG-50).
 *
 * <p>El pepper se aplica como HMAC-SHA256(pepper, contrasena) y el resultado en Base64 es lo que recibe el
 * delegado. Asi su entrada tiene longitud fija, y quien tenga la BBDD pero no el pepper no puede verificar los
 * hashes ni atacarlos por diccionario.
 */
public class CodificadorContrasenaConPepper implements PasswordEncoder {

    private static final String ALGORITMO_HMAC = "HmacSHA256";

    private final SecretKeySpec clavePepper;
    private final PasswordEncoder delegado;

    public CodificadorContrasenaConPepper(String pepper, PasswordEncoder delegado) {
        if (pepper == null || pepper.isBlank()) {
            throw new IllegalStateException(
                    "Falta el pepper de contrasenas: define la propiedad esibuy.seguridad.pepper (ESIBUY_PEPPER)");
        }
        if (delegado == null) {
            throw new IllegalArgumentException("El codificador delegado es obligatorio");
        }
        this.clavePepper = new SecretKeySpec(pepper.getBytes(StandardCharsets.UTF_8), ALGORITMO_HMAC);
        this.delegado = delegado;
    }

    @Override
    public String encode(CharSequence contrasena) {
        if (contrasena == null) {
            throw new IllegalArgumentException("La contrasena no puede ser nula");
        }
        return delegado.encode(aplicarPepper(contrasena));
    }

    @Override
    public boolean matches(CharSequence contrasena, String hashGuardado) {
        if (contrasena == null || hashGuardado == null || hashGuardado.isEmpty()) {
            return false;
        }
        return delegado.matches(aplicarPepper(contrasena), hashGuardado);
    }

    private String aplicarPepper(CharSequence contrasena) {
        try {
            // Mac no es thread-safe: una instancia por llamada.
            Mac mac = Mac.getInstance(ALGORITMO_HMAC);
            mac.init(clavePepper);
            byte[] resumen = mac.doFinal(contrasena.toString().getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(resumen);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo aplicar el pepper a la contrasena", e);
        }
    }
}
