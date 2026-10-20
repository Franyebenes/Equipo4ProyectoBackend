package com.esibuy.esibuy_backend.dto;

/*
 * Desactivar el segundo factor o regenerar los codigos de recuperacion: la contrasena de la cuenta y un codigo valido (el
 * TOTP del movil o uno de recuperacion). El toString() no incluye ninguno de los dos.
 */
public record SolicitudVerificacionDosFactoresDTO(String contrasena, String codigo) {

    @Override
    public String toString() {
        return "SolicitudVerificacionDosFactoresDTO[contrasena=" + DatosSensibles.OCULTO + ", codigo="
                + DatosSensibles.OCULTO + "]";
    }
}
