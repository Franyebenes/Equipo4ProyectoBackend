package com.esibuy.esibuy_backend.dto;

/**
 * Datos para anadir un metodo de pago, como en un formulario de tarjeta normal. El numero solo se usa para sacar los
 * 4 ultimos digitos y el CVV solo se comprueba: ninguno de los dos se guarda (PCI DSS prohibe guardar el CVV).
 */
public record MetodoPagoAltaDTO(String titular, String numeroTarjeta, String caducidad, String cvv) {

    /** Nunca se escriben el numero ni el CVV en un log. */
    @Override
    public String toString() {
        return "MetodoPagoAltaDTO[titular=***, numeroTarjeta=***, caducidad=" + caducidad + ", cvv=***]";
    }
}