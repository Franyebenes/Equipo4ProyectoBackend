package com.esibuy.esibuy_backend.dto;

import com.esibuy.esibuy_backend.modelo.Direccion;

/** No incluye idUsuario: el cliente ya sabe que son suyas. */
public record DireccionDTO(String id, String destinatario, String calle, String numero, String pisoPuerta,
                           String codigoPostal, String ciudad, String provincia, boolean predeterminada) {

    public static DireccionDTO desde(Direccion d) {
        return new DireccionDTO(d.getId(), d.getDestinatario(), d.getCalle(), d.getNumero(), d.getPisoPuerta(),
                d.getCodigoPostal(), d.getCiudad(), d.getProvincia(), d.isPredeterminada());
    }
}