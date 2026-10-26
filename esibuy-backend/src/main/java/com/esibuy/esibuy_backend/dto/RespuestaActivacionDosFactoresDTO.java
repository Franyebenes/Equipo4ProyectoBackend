package com.esibuy.esibuy_backend.dto;

/*
 * Lo que necesita la persona para dar de alta la cuenta en su aplicacion autenticadora: la URI que se dibuja en el QR y el
 * secreto en Base32 para escribirlo a mano. Se muestra una sola vez; el toString() no incluye ninguno de los dos.
 */
public record RespuestaActivacionDosFactoresDTO(String uri, String secreto) {

    @Override
    public String toString() {
        return "RespuestaActivacionDosFactoresDTO[uri=" + DatosSensibles.OCULTO + ", secreto=" + DatosSensibles.OCULTO + "]";
    }
}
