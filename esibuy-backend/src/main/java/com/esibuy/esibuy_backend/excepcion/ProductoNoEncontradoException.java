package com.esibuy.esibuy_backend.excepcion;

/**
 * El producto no existe o es de otro vendedor (HTTP 404). Los dos casos dan el mismo mensaje para no revelar los
 * productos de otros vendedores.
 */
public class ProductoNoEncontradoException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ProductoNoEncontradoException() {
        super("El producto no existe en tu catálogo");
    }
}
