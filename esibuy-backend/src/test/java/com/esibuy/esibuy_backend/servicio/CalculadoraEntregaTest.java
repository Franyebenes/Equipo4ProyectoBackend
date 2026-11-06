package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import com.esibuy.esibuy_backend.modelo.DireccionEnvio;
import com.esibuy.esibuy_backend.modelo.EstadoPedido;
import com.esibuy.esibuy_backend.modelo.LineaPedido;
import com.esibuy.esibuy_backend.modelo.Pedido;

/**
 * Estado del envio: fecha estimada de entrega. La que indico el vendedor o, si no, la fecha del pedido mas el plazo;
 * y ninguna cuando el pedido ya no va de camino.
 */
class CalculadoraEntregaTest {

    // 23:30 en UTC del 2 de noviembre es ya el 3 de noviembre en Espana
    private static final Instant FECHA = Instant.parse("2026-11-02T23:30:00Z");

    private final CalculadoraEntrega calculadora = new CalculadoraEntrega(5);

    @Test
    void sinFechaDelVendedor_esElDiaDelPedidoEnEspanaMasElPlazo() {
        assertThat(calculadora.estimada(pedido(EstadoPedido.PAGADO))).isEqualTo(LocalDate.of(2026, 11, 8));
    }

    @Test
    void elPlazoSeConfigura() {
        assertThat(new CalculadoraEntrega(2).estimada(pedido(EstadoPedido.EN_PREPARACION)))
                .isEqualTo(LocalDate.of(2026, 11, 5));
    }

    @Test
    void conFechaDelVendedor_esEsa() {
        Pedido enviado = pedido(EstadoPedido.ENVIADO);
        ReflectionTestUtils.setField(enviado, "fechaEntregaEstimada",
                CalculadoraEntrega.aInstante(LocalDate.of(2026, 11, 12)));
        assertThat(calculadora.estimada(enviado)).isEqualTo(LocalDate.of(2026, 11, 12));
    }

    @ParameterizedTest
    @EnumSource(value = EstadoPedido.class, names = {"ENTREGADO", "FINALIZADO", "CANCELADO", "PENDIENTE"})
    void siYaNoVaDeCamino_noHayFechaEstimada(EstadoPedido estado) {
        assertThat(calculadora.estimada(pedido(estado))).isNull();
    }

    @Test
    void unDiaSeGuardaComoSuInicioEnEspanaYSeRecuperaIgual() {
        LocalDate dia = LocalDate.of(2026, 11, 10);
        Instant guardado = CalculadoraEntrega.aInstante(dia);
        assertThat(guardado).isEqualTo(Instant.parse("2026-11-09T23:00:00Z"));
        assertThat(CalculadoraEntrega.aDia(guardado)).isEqualTo(dia);
    }

    private static Pedido pedido(EstadoPedido estado) {
        Pedido pedido = new Pedido("64c000000000000000000011", "64c000000000000000000001",
                new DireccionEnvio("Calle Mayor 1", "Ciudad Real", "13001"),
                List.of(new LineaPedido("64c000000000000000000021", "Teclado", 1, 18.0)), 18.0, FECHA);
        ReflectionTestUtils.setField(pedido, "estado", estado);
        return pedido;
    }
}
