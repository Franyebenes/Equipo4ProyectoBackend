package com.esibuy.esibuy_backend.modelo;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * Justificante del pago de un pedido (coleccion "pagos", HU-23). El pago es ficticio: solo se anota con que tarjeta
 * (sus 4 ultimos digitos) y cuanto se pago. Nunca se guarda el numero completo ni el CVV.
 */
@Document(collection = "pagos")
public class Pago {

    @Id
    private String id;
    @Field(name = "idPedido", targetType = FieldType.OBJECT_ID)
    private String idPedido;
    @Field(name = "idCliente", targetType = FieldType.OBJECT_ID)
    private String idCliente;
    @Field("ultimosDigitos")
    private String ultimosDigitos;
    @Field("importe")
    private double importe;
    @Field("fecha")
    private Instant fecha;

    /** Usado por Spring Data al leer de MongoDB. */
    protected Pago() {
    }

    public Pago(String idPedido, String idCliente, String ultimosDigitos, double importe, Instant fecha) {
        this.idPedido = idPedido;
        this.idCliente = idCliente;
        this.ultimosDigitos = ultimosDigitos;
        this.importe = importe;
        this.fecha = fecha;
    }

    public String getId() {
        return id;
    }

    public String getIdPedido() {
        return idPedido;
    }

    public String getIdCliente() {
        return idCliente;
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
        return "Pago[id=" + id + ", idPedido=" + idPedido + ", ultimosDigitos=" + ultimosDigitos + "]";
    }
}