package com.esibuy.esibuy_backend.servicio;

import com.esibuy.esibuy_backend.excepcion.FiltroNoValidoException;


public record FiltroCatalogoCliente(String busqueda, String idCategoria, Double precioMinimo, Double precioMaximo,
                                    Integer valoracionMinima, boolean soloDisponibles) {

    static final int VALORACION_MINIMA = 1;
    static final int VALORACION_MAXIMA = 5;
    static final String MENSAJE_PRECIO_NEGATIVO = "El precio no puede ser negativo";
    static final String MENSAJE_RANGO_PRECIO = "El precio mínimo no puede ser mayor que el máximo";
    static final String MENSAJE_VALORACION = "La valoración debe estar entre 1 y 5 estrellas";

    /** Sin filtros: todo el catalogo. */
    public static FiltroCatalogoCliente sinFiltros() {
        return new FiltroCatalogoCliente(null, null, null, null, null, false);
    }

    /** Comprueba que los filtros tienen sentido; si no, lanza FiltroNoValidoException (400) con el motivo. */
    public void validar() {
        if ((precioMinimo != null && precioMinimo < 0) || (precioMaximo != null && precioMaximo < 0)) {
            throw new FiltroNoValidoException(MENSAJE_PRECIO_NEGATIVO);
        }
        if (precioMinimo != null && precioMaximo != null && precioMinimo > precioMaximo) {
            throw new FiltroNoValidoException(MENSAJE_RANGO_PRECIO);
        }
        if (valoracionMinima != null
                && (valoracionMinima < VALORACION_MINIMA || valoracionMinima > VALORACION_MAXIMA)) {
            throw new FiltroNoValidoException(MENSAJE_VALORACION);
        }
    }
}