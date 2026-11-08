package com.esibuy.esibuy_backend.servicio;

import java.time.Instant;

/*
 * Token recien emitido: el valor en claro (para el enlace del correo) y sus fechas. Tests: GeneradorTokensTest (CP-TOK-06).
 *
 * @param token    valor en claro; solo existe aqui y en el correo
 * @param creadoEn instante de emision
 * @param caducaEn instante a partir del cual deja de valer
 */
public record TokenEmitido(String token, Instant creadoEn, Instant caducaEn) {

    // Nunca incluye el token: si alguien registra el objeto, el enlace no queda en el log. 
    @Override
    public String toString() {
        return "TokenEmitido[caducaEn=" + caducaEn + "]";
    }
}
