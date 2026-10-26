package com.esibuy.esibuy_backend.dto;

/*
 * Segundo paso del login: el codigo de la aplicacion autenticadora (o uno de recuperacion). El toString() no lo incluye.
 */
public record SolicitudSegundoFactorLoginDTO(String codigo) {

    @Override
    public String toString() {
        return "SolicitudSegundoFactorLoginDTO[codigo=" + DatosSensibles.OCULTO + "]";
    }
}
