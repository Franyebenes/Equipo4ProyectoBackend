package com.esibuy.esibuy_backend.modelo;

import java.util.List;

/**
 * Estado de un pedido. Se guarda en MongoDB con el mismo nombre. El pago es simulado: el pedido nace ya PAGADO.
 * PENDIENTE se conserva por el esquema de la BBDD aunque la aplicacion no lo use.
 *
 * Envio: el vendedor hace avanzar el pedido por PAGADO, EN_PREPARACION, ENVIADO, EN_REPARTO y ENTREGADO, siempre
 * hacia delante (puede saltarse pasos, pero nunca volver atras). FINALIZADO lo marca el cliente al confirmar que lo ha
 * recibido y CANCELADO, el cliente al cancelarlo mientras aun no se ha enviado (PAGADO o EN_PREPARACION).
 */
public enum EstadoPedido {
    PENDIENTE, PAGADO, EN_PREPARACION, ENVIADO, EN_REPARTO, ENTREGADO, FINALIZADO, CANCELADO;

    // Pasos del envio en orden. Se inicializa despues de las constantes del enum
    private static final List<EstadoPedido> ENVIO = List.of(PAGADO, EN_PREPARACION, ENVIADO, EN_REPARTO, ENTREGADO);

    /** Pasos del envio en orden: PAGADO, EN_PREPARACION, ENVIADO, EN_REPARTO y ENTREGADO. */
    public static List<EstadoPedido> pasosDelEnvio() {
        return ENVIO;
    }

    /** true mientras el pedido sigue su curso; false si ya ha terminado (finalizado o cancelado). */
    public boolean enProceso() {
        return this != FINALIZADO && this != CANCELADO;
    }

    /** true si el vendedor aun tiene que entregarlo: todavia no esta entregado, finalizado ni cancelado. */
    public boolean pendienteDeEntrega() {
        return this == PENDIENTE || !siguientesDelEnvio().isEmpty();
    }

    /** true si es uno de los pasos del envio (de PAGADO a ENTREGADO). */
    public boolean esPasoDelEnvio() {
        return ENVIO.contains(this);
    }

    /** Estados a los que el vendedor puede llevar el pedido desde este: los pasos del envio posteriores. */
    public List<EstadoPedido> siguientesDelEnvio() {
        int posicion = ENVIO.indexOf(this);
        return posicion < 0 ? List.of() : ENVIO.subList(posicion + 1, ENVIO.size());
    }

    /** true si el vendedor puede llevar el pedido de este estado a nuevo (solo hacia delante en el envio). */
    public boolean puedeAvanzarA(EstadoPedido nuevo) {
        return siguientesDelEnvio().contains(nuevo);
    }
}
