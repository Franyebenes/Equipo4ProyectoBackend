package com.esibuy.esibuy_backend.modelo;

/**
 * Estado de un pedido. Se guarda en MongoDB con el mismo nombre. El pago es simulado: el pedido nace ya PAGADO.
 * PENDIENTE se conserva por el esquema de la BBDD aunque la aplicacion no lo use.
 */
public enum EstadoPedido {
    PENDIENTE, PAGADO, ENVIADO, ENTREGADO, FINALIZADO, CANCELADO;

    /** true mientras el pedido sigue su curso; false si ya ha terminado (finalizado o cancelado). */
    public boolean enProceso() {
        return this != FINALIZADO && this != CANCELADO;
    }
}
