package com.esibuy.esibuy_backend.servicio;

import java.util.List;

public interface CatalogoAvatares {

    boolean esAvatarValido(String avatar);

    String avatarPorDefectoCliente();

    /** Debe ser distinto del avatar por defecto de cliente. */
    String avatarPorDefectoVendedor();

    List<String> listarAvatares();
}
