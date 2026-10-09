package com.esibuy.esibuy_backend.excepcion;

/** El vendedor tiene productos publicados y no se puede eliminar (HTTP 409), igual que una categoria con productos. */
public class UsuarioConProductosException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UsuarioConProductosException(String email, long numeroProductos) {
        super("No se puede eliminar a " + email + " porque tiene "
                + (numeroProductos == 1 ? "1 producto" : numeroProductos + " productos")
                + " en el catálogo. Elimina antes sus productos.");
    }
}
