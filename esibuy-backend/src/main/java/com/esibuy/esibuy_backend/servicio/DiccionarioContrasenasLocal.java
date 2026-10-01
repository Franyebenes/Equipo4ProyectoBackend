package com.esibuy.esibuy_backend.servicio;

import org.springframework.stereotype.Component;

/** Esqueleto: diccionario local (top de contrasenas comunes y filtradas). Pendiente de implementar. */
@Component
public class DiccionarioContrasenasLocal implements DiccionarioContrasenasProhibidas {

    @Override
    public boolean esComun(String contrasenaEnMinusculas) {
        throw new UnsupportedOperationException("Pendiente de implementar");
    }

    @Override
    public boolean estaFiltrada(String contrasena) {
        throw new UnsupportedOperationException("Pendiente de implementar");
    }
}
