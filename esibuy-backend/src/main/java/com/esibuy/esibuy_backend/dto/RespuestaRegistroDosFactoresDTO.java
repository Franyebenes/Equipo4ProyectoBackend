package com.esibuy.esibuy_backend.dto;

import java.util.List;

/*
 * Respuesta del registro: los datos de siempre mas los codigos de recuperacion recien generados (formato ABCD-EFGH-IJKL-MNOP),
 * que se muestran una sola vez: en la base de datos solo queda su hash. Nunca incluye la contrasena, su hash ni el secreto del
 * 2FA. El toString() no incluye los codigos.
 */
public record RespuestaRegistroDosFactoresDTO(String id, String email, String nombre, String mensaje,
                                              List<String> codigosRecuperacion) {

    @Override
    public String toString() {
        return "RespuestaRegistroDosFactoresDTO[id=" + id + ", email=" + email + ", nombre=" + nombre
                + ", mensaje=" + mensaje + ", codigosRecuperacion=" + DatosSensibles.OCULTO + "]";
    }
}
