package com.esibuy.esibuy_backend.modelo;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * Lista de productos de un cliente. "Favoritos" es la lista por defecto: existe siempre, puede estar vacia y no se
 * puede renombrar ni borrar. Coleccion "listas"; idUsuario e idProductos se guardan como ObjectId (como en Producto).
 */
@Document(collection = "listas")
public class ListaProductos {

    public static final String NOMBRE_FAVORITOS = "Favoritos";

    @Id
    private String id;
    @Field(name = "idUsuario", targetType = FieldType.OBJECT_ID)
    private String idUsuario;
    private String nombre;
    private boolean porDefecto;
    @Field(name = "idProductos", targetType = FieldType.OBJECT_ID)
    private List<String> idProductos = new ArrayList<>();

    /** Usado por Spring Data al leer de MongoDB. */
    protected ListaProductos() {
    }

    private ListaProductos(String idUsuario, String nombre, boolean porDefecto) {
        this.idUsuario = idUsuario;
        this.nombre = nombre;
        this.porDefecto = porDefecto;
    }

    public static ListaProductos nueva(String idUsuario, String nombre) {
        return new ListaProductos(idUsuario, nombre, false);
    }

    public static ListaProductos favoritos(String idUsuario) {
        return new ListaProductos(idUsuario, NOMBRE_FAVORITOS, true);
    }

    public void renombrar(String nuevoNombre) {
        if (porDefecto) {
            throw new IllegalStateException("La lista por defecto no se puede renombrar");
        }
        this.nombre = nuevoNombre;
    }

    /** @return true si se ha anadido; false si ya estaba (no se duplica) */
    public boolean anadirProducto(String idProducto) {
        if (idProductos.contains(idProducto)) {
            return false;
        }
        idProductos.add(idProducto);
        return true;
    }

    /** @return true si estaba y se ha quitado */
    public boolean quitarProducto(String idProducto) {
        return idProductos.remove(idProducto);
    }

    public boolean contiene(String idProducto) {
        return idProductos.contains(idProducto);
    }

    public String getId() { return id; }
    public String getIdUsuario() { return idUsuario; }
    public String getNombre() { return nombre; }
    public boolean isPorDefecto() { return porDefecto; }
    /** Copia de solo lectura: los cambios se hacen con anadirProducto y quitarProducto. */
    public List<String> getIdProductos() { return List.copyOf(idProductos); }

    @Override
    public String toString() {
        return "ListaProductos[id=" + id + ", porDefecto=" + porDefecto + ", productos=" + idProductos.size() + "]";
    }
}