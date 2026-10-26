package com.esibuy.esibuy_backend.dto;

/*
 * Iniciar la activacion del segundo factor: la contrasena de la cuenta, que se repite aunque haya sesion. El toString()
 * no la incluye.
 */
public record SolicitudActivacionDosFactoresDTO(String contrasena) {

    @Override
    public String toString() {
        return "SolicitudActivacionDosFactoresDTO[contrasena=" + DatosSensibles.OCULTO + "]";
    }
}
