package com.esibuy.esibuy_backend.servicio;

/** Listas de contrasenas comunes y filtradas en brechas publicas. */
public interface DiccionarioContrasenasProhibidas {

    /** Recibe la contrasena ya normalizada (NFC) y en minusculas. */
    boolean esComun(String contrasenaEnMinusculas);

    /** Recibe la contrasena normalizada (NFC), respetando mayusculas. */
    boolean estaFiltrada(String contrasena);
}
