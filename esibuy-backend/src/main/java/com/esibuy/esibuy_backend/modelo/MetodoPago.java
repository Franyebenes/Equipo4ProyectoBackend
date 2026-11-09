package com.esibuy.esibuy_backend.modelo;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * Metodo de pago de un cliente (coleccion "metodosPago"). El pago es ficticio: solo se guardan el titular, los 4
 * ultimos digitos y la caducidad. Nunca el numero completo ni el CVV.
 */
@Document(collection = "metodosPago")
public class MetodoPago {

    @Id
    private String id;
    @Field(name = "idCliente", targetType = FieldType.OBJECT_ID)
    private String idCliente;
    @Field("titular")
    private String titular;
    @Field("ultimosDigitos")
    private String ultimosDigitos;
    @Field("caducidad")
    private String caducidad;
    @Field("creadoEn")
    private Instant creadoEn;

    /** Usado por Spring Data al leer de MongoDB. */
    protected MetodoPago() {
    }

    public MetodoPago(String idCliente, String titular, String ultimosDigitos, String caducidad, Instant creadoEn) {
        this.idCliente = idCliente;
        this.titular = titular;
        this.ultimosDigitos = ultimosDigitos;
        this.caducidad = caducidad;
        this.creadoEn = creadoEn;
    }

    public String getId() {
        return id;
    }

    public String getIdCliente() {
        return idCliente;
    }

    public String getTitular() {
        return titular;
    }

    public String getUltimosDigitos() {
        return ultimosDigitos;
    }

    public String getCaducidad() {
        return caducidad;
    }

    public Instant getCreadoEn() {
        return creadoEn;
    }

    /** Sin datos del titular: puede acabar en un log. */
    @Override
    public String toString() {
        return "MetodoPago[id=" + id + ", ultimosDigitos=" + ultimosDigitos + "]";
    }
}