package com.esibuy.esibuy_backend.modelo;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

@Document(collection = "categorias")
public class Categoria {

    @Id
    private String id;

    @Indexed(unique = true)
    @Field("nombre")
    private String nombre;

    @Field("descripcion")
    private String descripcion;

    // Usado por Spring Data al leer de MongoDB.
    protected Categoria() {
    }

    public Categoria(String id, String nombre, String descripcion) {
        this.id = id;
        this.nombre = nombre;
        this.descripcion = descripcion;
    }

    public String getId() {
        return id;
    }

    public String getNombre() {
        return nombre;
    }

    public String getDescripcion() {
        return descripcion;
    }

    public void setNombre(String nombre) {
        this.nombre = nombre;
    }

    public void setDescripcion(String descripcion) {
        this.descripcion = descripcion;
    }
}