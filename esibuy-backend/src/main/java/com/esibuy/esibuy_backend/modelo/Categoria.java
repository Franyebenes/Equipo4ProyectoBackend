package com.esibuy.esibuy_backend.modelo;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

@Document(collection = "categorias")
public class Categoria {

    @Id
    private String id;
    @Field("nombre")
    private String nombre;

    public String getId() {
        return id;
    }

    public String getNombre() {
        return nombre;
    }
}
