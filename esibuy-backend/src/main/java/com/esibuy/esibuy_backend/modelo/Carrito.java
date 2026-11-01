package com.esibuy.esibuy_backend.modelo;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * Carrito de un cliente (coleccion "carritos"). Un carrito por cliente; idCliente e idProducto se guardan como ObjectId.
 */
@Document(collection = "carritos")
public class Carrito {

    @Id
    private String id;
    @Field(name = "idCliente", targetType = FieldType.OBJECT_ID)
    private String idCliente;
    @Field("lineas")
    private List<LineaCarrito> lineas = new ArrayList<>();
    // Ultima vez que el cliente cambio el carrito: pasadas 24 horas sin tocarlo, lo reservado vuelve al stock
    @Field("ultimaModificacion")
    private Instant ultimaModificacion;

    /** Usado por Spring Data al leer de MongoDB. */
    protected Carrito() {
    }

    public Carrito(String idCliente) {
        this.idCliente = idCliente;
    }

    public String getId() {
        return id;
    }

    public String getIdCliente() {
        return idCliente;
    }

    public List<LineaCarrito> getLineas() {
        return lineas;
    }

    public Instant getUltimaModificacion() {
        return ultimaModificacion;
    }

    public void marcarModificado(Instant momento) {
        this.ultimaModificacion = momento;
    }

    public Optional<LineaCarrito> buscarLinea(String idProducto) {
        return lineas.stream().filter(l -> l.getIdProducto().equals(idProducto)).findFirst();
    }

    /** Si el producto ya esta en el carrito suma la cantidad; si no, anade una linea nueva. */
    public void anadir(String idProducto, int cantidad) {
        buscarLinea(idProducto).ifPresentOrElse(
                linea -> linea.cambiarCantidad(linea.getCantidad() + cantidad),
                () -> lineas.add(new LineaCarrito(idProducto, cantidad)));
    }

    public void quitar(String idProducto) {
        lineas.removeIf(l -> l.getIdProducto().equals(idProducto));
    }

    public void vaciar() {
        lineas.clear();
    }
}