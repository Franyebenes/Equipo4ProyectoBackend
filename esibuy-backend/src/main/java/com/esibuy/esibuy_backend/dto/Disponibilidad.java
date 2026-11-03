package com.esibuy.esibuy_backend.dto;

public enum Disponibilidad {
    
    /** 5 unidades o mas. */
    DISPONIBLE,
    /** Menos de 5 unidades: "Queda poco stock". */
    POCO_STOCK,
    /** Sin unidades. */
    AGOTADO;

    /** Por debajo de estas unidades se avisa de que queda poco stock. */
    public static final int UMBRAL_POCO_STOCK = 5;

    /** Un producto sin stock informado se trata como agotado. */
    public static Disponibilidad de(Integer stock) {
        if (stock == null || stock <= 0) {
            return AGOTADO;
        }
        return stock < UMBRAL_POCO_STOCK ? POCO_STOCK : DISPONIBLE;
    }
}
