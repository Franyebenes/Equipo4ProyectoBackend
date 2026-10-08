package com.esibuy.esibuy_backend.modelo;

import org.springframework.data.mongodb.core.mapping.Field;

/**
 * Subdocumento "precio" de productos: precio base y descuentos en porcentaje (0-100), segun el esquema de la
 * coleccion en MongoDB.
 */
public class PrecioProducto {

    @Field("base")
    private Double base;
    @Field("descuento")
    private Double descuento;
    @Field("descuentoPremium")
    private Double descuentoPremium;

    /** Usado por Spring Data al leer de MongoDB. */
    protected PrecioProducto() {
    }

    public PrecioProducto(Double base, Double descuento, Double descuentoPremium) {
        this.base = base;
        this.descuento = descuento;
        this.descuentoPremium = descuentoPremium;
    }

    public Double getBase() {
        return base;
    }

    public Double getDescuento() {
        return descuento;
    }

    public Double getDescuentoPremium() {
        return descuentoPremium;
    }
}
