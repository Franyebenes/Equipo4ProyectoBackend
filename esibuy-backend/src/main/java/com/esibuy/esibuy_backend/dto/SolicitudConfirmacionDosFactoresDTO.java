package com.esibuy.esibuy_backend.dto;

/*
 * Confirmar la activacion del segundo factor con el primer codigo de la aplicacion autenticadora. El toString() no lo
 * incluye.
 */
public record SolicitudConfirmacionDosFactoresDTO(String codigo) {

    @Override
    public String toString() {
        return "SolicitudConfirmacionDosFactoresDTO[codigo=" + DatosSensibles.OCULTO + "]";
    }
}
