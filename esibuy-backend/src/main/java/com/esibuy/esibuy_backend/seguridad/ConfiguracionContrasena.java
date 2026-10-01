package com.esibuy.esibuy_backend.seguridad;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class ConfiguracionContrasena {
    //deberíamos meterlas también en la interfaz?
    static final int LONGITUD_SALT_BYTES = 16;
    static final int LONGITUD_HASH_BYTES = 32;
    static final int PARALELISMO = 1;
    static final int MEMORIA_KIB = 64 * 1024;
    static final int ITERACIONES = 3;

    @Bean
    public PasswordEncoder codificadorContrasena(@Value("${esibuy.seguridad.pepper:}") String pepper) {
        if (pepper == null || pepper.isBlank()) {
            throw new IllegalStateException(
                    "Falta el pepper de contrasenas: define la propiedad esibuy.seguridad.pepper (ESIBUY_PEPPER)");
        }
        PasswordEncoder argon2id = new Argon2PasswordEncoder(
                LONGITUD_SALT_BYTES, LONGITUD_HASH_BYTES, PARALELISMO, MEMORIA_KIB, ITERACIONES);
        return new CodificadorContrasenaConPepper(pepper, argon2id);
    }
}
