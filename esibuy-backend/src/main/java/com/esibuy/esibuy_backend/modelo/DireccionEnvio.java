package com.esibuy.esibuy_backend.modelo;

import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;
/** Subdocumento "direccionEnvio" de pedidos: a donde se envia el pedido. */
public class DireccionEnvio {

    @Field(name = "idDireccion", targetType = FieldType.OBJECT_ID)
    private String idDireccion;
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

    public DireccionEnvio(String calle, String ciudad, String codigoPostal, String idDireccion) {
        this(calle, ciudad, codigoPostal);
        this.idDireccion = idDireccion;
    }

    public String getIdDireccion() {
        return idDireccion;
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
