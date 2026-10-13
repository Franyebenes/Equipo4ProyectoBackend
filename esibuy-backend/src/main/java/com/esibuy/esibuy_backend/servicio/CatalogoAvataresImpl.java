package com.esibuy.esibuy_backend.servicio;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * Catalogo de avatares definido en configuracion ({@link PropiedadesAvatares}).
 */
@Component
public class CatalogoAvataresImpl implements CatalogoAvatares {

    private final PropiedadesAvatares propiedades;
    private final Set<String> disponibles;

    public CatalogoAvataresImpl(PropiedadesAvatares propiedades) {
        this.propiedades = propiedades;
        this.disponibles = Set.copyOf(propiedades.disponibles());
    }

    @Override
    public boolean esAvatarValido(String avatar) {
        return avatar != null && disponibles.contains(avatar);
    }

    @Override
    public String avatarPorDefectoCliente() {
        return propiedades.porDefectoCliente();
    }

    @Override
    public String avatarPorDefectoVendedor() {
        return propiedades.porDefectoVendedor();
    }

    /** En el orden de la configuracion, que es el que vera el usuario en el formulario. */
    @Override
    public List<String> listarAvatares() {
        return propiedades.disponibles();
    }
}
