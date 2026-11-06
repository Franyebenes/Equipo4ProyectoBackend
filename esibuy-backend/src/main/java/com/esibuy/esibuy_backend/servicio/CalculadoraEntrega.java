package com.esibuy.esibuy_backend.servicio;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.esibuy.esibuy_backend.modelo.EstadoPedido;
import com.esibuy.esibuy_backend.modelo.Pedido;

/**
 * Estado del envio: fecha estimada de entrega de un pedido. Si el vendedor indico una al enviarlo, esa; si no, la
 * fecha del pedido mas el plazo configurado (esibuy.pedidos.dias-entrega-estimada, 5 dias por defecto). Solo tiene
 * sentido mientras el pedido va de camino: una vez entregado, finalizado o cancelado no hay estimacion.
 *
 * Las fechas de entrega son dias, no instantes: se pasan de uno a otro con la hora de Espana.
 */
@Component
public class CalculadoraEntrega {

    /** Zona horaria de la tienda, para saber en que dia cae cada instante. */
    public static final ZoneId ZONA = ZoneId.of("Europe/Madrid");

    private final int diasEntrega;

    public CalculadoraEntrega(@Value("${esibuy.pedidos.dias-entrega-estimada:5}") int diasEntrega) {
        this.diasEntrega = diasEntrega;
    }

    /** Fecha estimada de entrega, o null si el pedido ya no va de camino (entregado, finalizado o cancelado). */
    public LocalDate estimada(Pedido pedido) {
        EstadoPedido estado = pedido.getEstado();
        if (!estado.esPasoDelEnvio() || estado == EstadoPedido.ENTREGADO) {
            return null;
        }
        Instant indicada = pedido.getFechaEntregaEstimada();
        return indicada != null ? aDia(indicada) : aDia(pedido.getFecha()).plusDays(diasEntrega);
    }

    public static LocalDate aDia(Instant instante) {
        return instante.atZone(ZONA).toLocalDate();
    }

    public static Instant aInstante(LocalDate dia) {
        return dia.atStartOfDay(ZONA).toInstant();
    }
}
