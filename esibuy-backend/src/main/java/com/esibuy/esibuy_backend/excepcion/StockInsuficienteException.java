package com.esibuy.esibuy_backend.excepcion;

/** No quedan unidades suficientes del producto para reservarlas en el carrito (HTTP 409). */
public class StockInsuficienteException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public StockInsuficienteException() {
        super("No hay stock suficiente de este producto");
    }
}