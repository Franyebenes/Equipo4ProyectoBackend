package com.esibuy.esibuy_backend.excepcion;

/** La categoría tiene productos asociados y no se puede eliminar (HTTP 409). */
public class CategoriaConProductosException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CategoriaConProductosException(String nombreCategoria, long numeroProductos) {
        super("No se puede eliminar la categoría «" + nombreCategoria + "» porque tiene "
                + (numeroProductos == 1 ? "1 producto asociado" : numeroProductos + " productos asociados"));
    }
}