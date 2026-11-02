package com.esibuy.esibuy_backend.modelo;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * Justificante del pago de la cuota Premium (coleccion "pagosSuscripcion"). El pago es ficticio, como el de
 * los pedidos (Pago, coleccion "pagos"): solo se anota con que tarjeta (4 ultimos digitos), cuanto y cuando.
 */
@Document(collection = "pagosSuscripcion")
public class PagoSuscripcion {

    public static final String CONCEPTO_PREMIUM = "SUSCRIPCION_PREMIUM";

    @Id
    private String id;
    @Field(name = "idCliente", targetType = FieldType.OBJECT_ID)
    private String idCliente;
    @Field("concepto")
    private String concepto;
    @Field("ultimosDigitos")
    private String ultimosDigitos;
    @Field("importe")
    private double importe;
    @Field("fecha")
    private Instant fecha;

    /** Usado por Spring Data al leer de MongoDB. */
    protected PagoSuscripcion() {
    }

    public PagoSuscripcion(String idCliente, String ultimosDigitos, double importe, Instant fecha) {
        this.idCliente = idCliente;
        this.concepto = CONCEPTO_PREMIUM;
        this.ultimosDigitos = ultimosDigitos;
        this.importe = importe;
        this.fecha = fecha;
    }

    public String getId() {
        return id;
    }

    public String getIdCliente() {
        return idCliente;
    }

    public String getConcepto() {
        return concepto;
    }

    public String getUltimosDigitos() {
        return ultimosDigitos;
    }

    public double getImporte() {
        return importe;
    }

    public Instant getFecha() {
        return fecha;
    }

    @Override
    public String toString() {
        return "PagoSuscripcion[id=" + id + ", ultimosDigitos=" + ultimosDigitos + "]";
    }
}