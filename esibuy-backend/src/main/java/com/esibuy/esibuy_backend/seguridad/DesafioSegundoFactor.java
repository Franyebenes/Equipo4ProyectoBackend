package com.esibuy.esibuy_backend.seguridad;

import java.io.Serializable;
import java.time.Instant;

import com.esibuy.esibuy_backend.servicio.ResultadoAutenticacion;

/*
 * Desafio pendiente entre el primer paso del login (contrasena correcta de una cuenta con 2FA) y el segundo (el codigo):
 * quien es el usuario, cuando empezo y cuantos codigos incorrectos lleva. Es inmutable y Serializable, porque vive en la
 * sesion HTTP y el contenedor puede persistirla al reiniciarse. No concede nada: ver GestorDesafioSegundoFactor.
 */
public record DesafioSegundoFactor(ResultadoAutenticacion usuario, Instant creadoEn, int intentosFallidos)
        implements Serializable {

    /** El mismo desafio con un codigo fallido mas; no cambia la fecha en que empezo. */
    public DesafioSegundoFactor conUnFallo() {
        return new DesafioSegundoFactor(usuario, creadoEn, intentosFallidos + 1);
    }
}
