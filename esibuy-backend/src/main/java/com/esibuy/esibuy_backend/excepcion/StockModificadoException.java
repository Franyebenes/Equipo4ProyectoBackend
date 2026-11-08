package com.esibuy.esibuy_backend.excepcion;

/**
 * El stock del producto ha cambiado desde que el vendedor abrio la pantalla (p. ej. un cliente ha reservado unidades
 * en su carrito) y el vendedor queria guardar otra cantidad (HTTP 409). No se guarda para no pisar la cantidad actual
 * con una antigua; el vendedor ve la cantidad actual y decide.
 */
public class StockModificadoException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int stockActual;

    public StockModificadoException(int stockActual) {
        super("El stock ha cambiado mientras lo editabas: ahora hay " + unidades(stockActual)
                + ". Revisa la cantidad y vuelve a guardar.");
        this.stockActual = stockActual;
    }

    public int getStockActual() {
        return stockActual;
    }

    private static String unidades(int stock) {
        return stock == 1 ? "1 unidad" : stock + " unidades";
    }
}
