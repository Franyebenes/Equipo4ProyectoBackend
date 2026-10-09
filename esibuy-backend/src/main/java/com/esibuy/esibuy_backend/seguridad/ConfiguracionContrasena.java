package com.esibuy.esibuy_backend.seguridad;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Codificador de contrasenas de la aplicacion: Argon2id envuelto en {@link CodificadorContrasenaConPepper}.
 * Tests: CodificadorContrasenaTest (CP-REG-50 y CP-REG-51).
 *
 * <p>Parametros de Argon2id dentro de los rangos del documento de requisitos (64-256 MB, 2-4 iteraciones,
 * paralelismo 1-2). Se quedan aqui y no en {@code Constantes} porque solo los usa esta clase.
 */
@Configuration
public class ConfiguracionContrasena {

    static final int LONGITUD_SALT_BYTES = 16;
    static final int LONGITUD_HASH_BYTES = 32;
    static final int PARALELISMO = 1;
    static final int MEMORIA_KIB = 64 * 1024;
    static final int ITERACIONES = 3;

    /** Sin pepper la aplicacion no arranca: el constructor del codificador lanza IllegalStateException. */
    @Bean
    public PasswordEncoder codificadorContrasena(@Value("${esibuy.seguridad.pepper:}") String pepper) {
        PasswordEncoder argon2id = new Argon2PasswordEncoder(
                LONGITUD_SALT_BYTES, LONGITUD_HASH_BYTES, PARALELISMO, MEMORIA_KIB, ITERACIONES);
        return new CodificadorContrasenaConPepper(pepper, argon2id);
    }
}
