package com.esibuy.esibuy_backend.modelo;

import org.springframework.data.mongodb.core.mapping.Field;

/** Subdocumento "direccionEnvio" de pedidos: a donde se envia el pedido. */
public class DireccionEnvio {

    @Field("calle")
    private String calle;
    @Field("ciudad")
    private String ciudad;
    @Field("codigoPostal")
    private String codigoPostal;

    /** Usado por Spring Data al leer de MongoDB. */
    protected DireccionEnvio() {
    }

    public DireccionEnvio(String calle, String ciudad, String codigoPostal) {
        this.calle = calle;
        this.ciudad = ciudad;
        this.codigoPostal = codigoPostal;
    }

    public String getCalle() {
        return calle;
    }

    public String getCiudad() {
        return ciudad;
    }

    public String getCodigoPostal() {
        return codigoPostal;
    }
}
