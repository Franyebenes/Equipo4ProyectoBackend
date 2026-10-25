package com.esibuy.esibuy_backend.excepcion;

/** El producto es solo para clientes premium y el cliente de la sesion no lo es (HTTP 403). */
public class ProductoSoloPremiumException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ProductoSoloPremiumException() {
        super("Este producto solo está disponible para clientes premium");
    }
}
