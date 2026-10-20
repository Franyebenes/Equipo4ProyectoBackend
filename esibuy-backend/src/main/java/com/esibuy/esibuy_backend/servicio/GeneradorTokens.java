package com.esibuy.esibuy_backend.servicio;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

import org.springframework.stereotype.Component;

/*
 * Genera los tokens de recuperacion y calcula su hash. El token son 256 bits de un generador criptograficamente seguro
 * en base64url sin relleno (43 caracteres). En la base de datos solo se guarda su SHA-256: como el token ya es
 * aleatorio y de alta entropia basta un hash rapido (no hace falta Argon2, que protege contrasenas elegidas por
 * personas). Tests: GeneradorTokensTest (CP-TOK-01 a CP-TOK-05).
 */
@Component
public class GeneradorTokens {

    private static final int BYTES_DEL_TOKEN = 32;    // 256 bits.


    private final SecureRandom aleatorio;

    public GeneradorTokens() {
        this(new SecureRandom());
    }

    // Para los tests: permite sustituir la fuente de aleatoriedad.
    GeneradorTokens(SecureRandom aleatorio) {
        this.aleatorio = aleatorio;
    }

    // Token nuevo: 256 bits aleatorios en base64url sin relleno.
    public String nuevoToken() {
        byte[] bytes = new byte[BYTES_DEL_TOKEN];
        aleatorio.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // SHA-256 del token en hexadecimal minuscula (64 caracteres).
    public static String hash(String token) {
        if (token == null || token.isEmpty()) {
            throw new IllegalArgumentException("El token es obligatorio");
        }
        try {
            byte[] resumen = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(resumen);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no esta disponible en esta JVM", e);
        }
    }
}
