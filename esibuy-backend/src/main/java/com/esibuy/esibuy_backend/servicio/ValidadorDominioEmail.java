package com.esibuy.esibuy_backend.servicio;

/** Comprueba que el dominio del email recibe correo (registro MX). Decision D6. */
public interface ValidadorDominioEmail {

    boolean tieneDominioValido(String email);
}
