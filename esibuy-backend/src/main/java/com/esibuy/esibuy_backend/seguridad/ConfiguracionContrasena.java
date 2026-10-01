package com.esibuy.esibuy_backend.seguridad;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Configuracion del codificador de contrasenas. Tests: CP-REG-50 y CP-REG-51.
 *
 * TODO: Argon2id con memoria de 64 a 256 MB, 2 a 4 iteraciones y paralelismo 1 o 2, envuelto en
 * CodificadorContrasenaConPepper. Si el pepper es null o esta en blanco, fallar con IllegalStateException.
 * El pepper se lee de configuracion (variable de entorno o keystore), nunca va en el codigo.
 */
@Configuration
public class ConfiguracionContrasena {

    @Bean
    public PasswordEncoder codificadorContrasena(@Value("${esibuy.seguridad.pepper:}") String pepper) {
        throw new UnsupportedOperationException("Pendiente de implementar");
    }
}
