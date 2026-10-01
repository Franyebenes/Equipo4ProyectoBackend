package com.esibuy.esibuy_backend.servicio;

import org.springframework.stereotype.Component;

import java.util.List;

/** Esqueleto: permite arrancar el contexto de Spring. Pendiente de implementar. */
@Component
public class CatalogoAvataresImpl implements CatalogoAvatares {

    @Override
    public boolean esAvatarValido(String avatar) {
        throw new UnsupportedOperationException("Pendiente de implementar");
    }

    @Override
    public String avatarPorDefectoCliente() {
        throw new UnsupportedOperationException("Pendiente de implementar");
    }

    @Override
    public String avatarPorDefectoVendedor() {
        throw new UnsupportedOperationException("Pendiente de implementar");
    }

    @Override
    public List<String> listarAvatares() {
        throw new UnsupportedOperationException("Pendiente de implementar");
    }
}
