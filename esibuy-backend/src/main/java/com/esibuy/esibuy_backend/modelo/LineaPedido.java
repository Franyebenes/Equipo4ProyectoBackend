package com.esibuy.esibuy_backend.modelo;

import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * Una linea del pedido: que producto, cuantas unidades y a que precio se compro cada una (ya con el descuento que
 * tenia el cliente). Se guarda tambien el nombre para que el historial no dependa de que el producto siga existiendo.
 */
public class LineaPedido {

    @Field(name = "idProducto", targetType = FieldType.OBJECT_ID)
    private String idProducto;
    @Field("nombre")
    private String nombre;
    @Field("cantidad")
    private int cantidad;
    @Field("precioUnitario")
    private double precioUnitario;

    /** Usado por Spring Data al leer de MongoDB. */
    protected LineaPedido() {
    }

    public LineaPedido(String idProducto, String nombre, int cantidad, double precioUnitario) {
        this.idProducto = idProducto;
        this.nombre = nombre;
        this.cantidad = cantidad;
        this.precioUnitario = precioUnitario;
    }

    public String getIdProducto() {
        return idProducto;
    }

    public String getNombre() {
        return nombre;
    }

    public int getCantidad() {
        return cantidad;
    }

    public double getPrecioUnitario() {
        return precioUnitario;
    }
}
