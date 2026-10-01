package com.esibuy.esibuy_backend.servicio;

import org.springframework.stereotype.Component;

/** Esqueleto: consulta del registro MX del dominio. Pendiente de implementar. */
@Component
public class ValidadorDominioEmailMx implements ValidadorDominioEmail {

    @Override
    public boolean tieneDominioValido(String email) {
        throw new UnsupportedOperationException("Pendiente de implementar");
    }
}
