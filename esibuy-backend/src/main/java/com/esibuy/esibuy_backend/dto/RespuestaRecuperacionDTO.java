package com.esibuy.esibuy_backend.dto;

/*
 * Respuesta a la solicitud de recuperacion. Es siempre la misma, exista o no la cuenta y sea cual sea su estado: no
 * confirma ni niega que el correo este registrado. El texto lo ha fijado el equipo.
 */
public record RespuestaRecuperacionDTO(String mensaje) {

    public static final String MENSAJE_GENERICO = "Se ha enviado un correo de recuperación";

    public static RespuestaRecuperacionDTO generica() {
        return new RespuestaRecuperacionDTO(MENSAJE_GENERICO);
    }
}
