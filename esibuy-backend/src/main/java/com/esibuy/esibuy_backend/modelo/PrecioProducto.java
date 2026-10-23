package com.esibuy.esibuy_backend.modelo;

import org.springframework.data.mongodb.core.mapping.Field;

/**
 * Subdocumento "precio" de productos: precio base y descuentos en porcentaje (0-100), segun el esquema de la
 * coleccion en MongoDB. El descuento es para todos los clientes; el premium se aplica ademas, solo a los premium.
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

    /**
     * Precio que paga el cliente. Primero se aplica el descuento para todos y, si el cliente es premium, sobre ese
     * precio el descuento premium. 
     */
    public double precioFinal(boolean clientePremium) {
        double resultado = base * (1 - porcentaje(descuento));
        if (clientePremium) {
            resultado *= 1 - porcentaje(descuentoPremium);
        }
        // Redondeado a centimos
        return Math.round(resultado * 100) / 100.0;
    }

    private static double porcentaje(Double descuento) {
        return descuento == null ? 0 : descuento / 100;
    }
}
