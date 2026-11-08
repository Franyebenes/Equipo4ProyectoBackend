package com.esibuy.esibuy_backend.modelo;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * Valoracion (de 1 a 5) que un cliente da a un producto de un pedido finalizado (coleccion "valoraciones"). El
 * indice unico de la BBDD por cliente, producto y pedido impide valorar dos veces lo mismo.
 */
@Document(collection = "valoraciones")
public class Valoracion {

    @Id
    private String id;
    @Field(name = "idProducto", targetType = FieldType.OBJECT_ID)
    private String idProducto;
    @Field(name = "idCliente", targetType = FieldType.OBJECT_ID)
    private String idCliente;
    @Field(name = "idPedido", targetType = FieldType.OBJECT_ID)
    private String idPedido;
    @Field("puntos")
    private int puntos;

    /** Usado por Spring Data al leer de MongoDB. */
    protected Valoracion() {
    }

    public Valoracion(String idProducto, String idCliente, String idPedido, int puntos) {
        this.idProducto = idProducto;
        this.idCliente = idCliente;
        this.idPedido = idPedido;
        this.puntos = puntos;
    }

    public String getId() {
        return id;
    }

    public String getIdProducto() {
        return idProducto;
    }

    public String getIdCliente() {
        return idCliente;
    }

    public String getIdPedido() {
        return idPedido;
    }

    public int getPuntos() {
        return puntos;
    }
}
