package com.esibuy.esibuy_backend.modelo;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/** Coleccion "categories". */
@Document(collection = "categories")
public class Categoria {

    @Id
    private String id;
    @Field("name")
    private String nombre;

    public String getId() {
        return id;
    }

    public String getNombre() {
        return nombre;
    }
}
