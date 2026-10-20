package com.esibuy.esibuy_backend.modelo;

import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * Una linea del carrito: que producto y cuantas unidades. Va dentro del documento del carrito.
 */
public class LineaCarrito {

    @Field(name = "idProducto", targetType = FieldType.OBJECT_ID)
    private String idProducto;
    @Field("cantidad")
    private int cantidad;

    /** Usado por Spring Data al leer de MongoDB. */
    protected LineaCarrito() {
    }

    public LineaCarrito(String idProducto, int cantidad) {
        this.idProducto = idProducto;
        this.cantidad = cantidad;
    }

    public String getIdProducto() {
        return idProducto;
    }

    public int getCantidad() {
        return cantidad;
    }

    public void cambiarCantidad(int cantidad) {
        this.cantidad = cantidad;
    }
}