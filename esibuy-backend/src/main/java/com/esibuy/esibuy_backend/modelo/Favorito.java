package com.esibuy.esibuy_backend.modelo;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

// HU-31 Favoritos: un producto que un cliente ha marcado como favorito (coleccion "favoritos"). Un documento por
// cliente y producto; el servicio impide repetirlo.
@Document(collection = "favoritos")
public class Favorito {

    @Id
    private String id;
    @Field(name = "idCliente", targetType = FieldType.OBJECT_ID)
    private String idCliente;
    @Field(name = "idProducto", targetType = FieldType.OBJECT_ID)
    private String idProducto;
    @Field("anadidoEn")
    private Instant anadidoEn;

    /** Usado por Spring Data al leer de MongoDB. */
    protected Favorito() {
    }

    public Favorito(String idCliente, String idProducto, Instant anadidoEn) {
        this.idCliente = idCliente;
        this.idProducto = idProducto;
        this.anadidoEn = anadidoEn;
    }

    public String getId() {
        return id;
    }

    public String getIdCliente() {
        return idCliente;
    }

    public String getIdProducto() {
        return idProducto;
    }

    public Instant getAnadidoEn() {
        return anadidoEn;
    }
}
