package com.esibuy.esibuy_backend.modelo;

import org.springframework.data.mongodb.core.mapping.Field;

/**
 * Subdocumento "precio" de productos: precio base y descuentos en porcentaje (0-100), segun el esquema de la
 * coleccion en MongoDB. El descuento es para todos los clientes; el premium es un descuento extra solo para los
 * premium, que se suma al anterior (10 % + 15 % = 25 %).
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
     * Precio que paga el cliente. Todos tienen el descuento general; un premium tiene ademas el premium, y los dos
     * se suman: con un 10 % y un 15 % premium, el premium paga un 25 % menos. Redondeado a centimos y nunca
     * negativo (el alta y la modificacion ya impiden que la suma pase del 100 %).
     */
    public double precioFinal(boolean clientePremium) {
        double descuentoTotal = descuentoTotal(clientePremium);
        double resultado = base * Math.max(0, 1 - descuentoTotal / 100);
        return Math.round(resultado * 100) / 100.0;
    }

    /** Porcentaje de descuento que tiene el cliente: el general y, si es premium, tambien el premium. */
    public double descuentoTotal(boolean clientePremium) {
        double total = valor(descuento);
        if (clientePremium) {
            total += valor(descuentoPremium);
        }
        return total;
    }

    private static double valor(Double porcentaje) {
        return porcentaje == null ? 0 : porcentaje;
    }
}
