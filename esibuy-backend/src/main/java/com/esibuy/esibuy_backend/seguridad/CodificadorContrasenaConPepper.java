package com.esibuy.esibuy_backend.seguridad;

import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Anade un pepper secreto de la aplicacion a la contrasena antes de delegar en el codificador real
 * (Argon2id). Tests: CodificadorContrasenaTest (CP-REG-49 y CP-REG-50).
 *
 * TODO: lanzar IllegalStateException si el pepper es null o esta en blanco.
 */
public class CodificadorContrasenaConPepper implements PasswordEncoder {

    private final String pepper;
    private final PasswordEncoder delegado;

    public CodificadorContrasenaConPepper(String pepper, PasswordEncoder delegado) {
        this.pepper = pepper;
        this.delegado = delegado;
    }

    @Override
    public String encode(CharSequence contrasena) {
        throw new UnsupportedOperationException("Pendiente de implementar");
    }

    @Override
    public boolean matches(CharSequence contrasena, String hashGuardado) {
        throw new UnsupportedOperationException("Pendiente de implementar");
    }
}
