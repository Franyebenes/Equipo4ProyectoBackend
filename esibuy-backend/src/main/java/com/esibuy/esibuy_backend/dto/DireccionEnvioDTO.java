package com.esibuy.esibuy_backend.dto;

/**
 * Direccion a la que se envia el pedido. Si llega idDireccion, se usa esa direccion de la libreta del cliente (y se
 * ignoran calle, ciudad y codigoPostal); si no, la que el cliente ha escrito al tramitarlo.
 */
public record DireccionEnvioDTO(String calle, String ciudad, String codigoPostal, String idDireccion) {

    /** Direccion escrita a mano, sin libreta. */
    public DireccionEnvioDTO(String calle, String ciudad, String codigoPostal) {
        this(calle, ciudad, codigoPostal, null);
    }
}