package com.esibuy.esibuy_backend.dto;

import java.util.List;

/*
 * Los codigos de recuperacion recien generados, con el formato para mostrarlos (ABCD-EFGH-IJKL-MNOP). Se muestran una sola
 * vez: en la base de datos solo queda su hash. El toString() no los incluye.
 */
public record RespuestaCodigosRecuperacionDTO(List<String> codigosRecuperacion) {

    @Override
    public String toString() {
        return "RespuestaCodigosRecuperacionDTO[codigosRecuperacion=" + DatosSensibles.OCULTO + "]";
    }
}
