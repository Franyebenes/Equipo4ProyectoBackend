package com.esibuy.esibuy_backend.modelo;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Procesar estado del pedido: recorrido del envio (PAGADO, EN_PREPARACION, ENVIADO, EN_REPARTO, ENTREGADO), que solo
 * avanza, y fecha de la ultima actualizacion del pedido. Estado del envio: cada cambio se anota en su seguimiento.
 */
class EstadoPedidoTest {

    private static final Instant FECHA = Instant.parse("2026-11-02T09:30:00Z");
    private static final Instant DESPUES = Instant.parse("2026-11-03T12:00:00Z");

    @Test
    void desdePagado_sePuedeIrACualquierPasoPosteriorDelEnvio() {
        assertThat(EstadoPedido.PAGADO.siguientesDelEnvio()).containsExactly(EstadoPedido.EN_PREPARACION,
                EstadoPedido.ENVIADO, EstadoPedido.EN_REPARTO, EstadoPedido.ENTREGADO);
    }

    @Test
    void desdeEnviado_soloQuedanEnRepartoYEntregado() {
        assertThat(EstadoPedido.ENVIADO.siguientesDelEnvio())
                .containsExactly(EstadoPedido.EN_REPARTO, EstadoPedido.ENTREGADO);
        assertThat(EstadoPedido.ENVIADO.puedeAvanzarA(EstadoPedido.ENTREGADO)).isTrue();
        assertThat(EstadoPedido.ENVIADO.puedeAvanzarA(EstadoPedido.EN_PREPARACION)).isFalse();
        assertThat(EstadoPedido.ENVIADO.puedeAvanzarA(EstadoPedido.ENVIADO)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = EstadoPedido.class, names = {"ENTREGADO", "FINALIZADO", "CANCELADO", "PENDIENTE"})
    void entregadoTerminadoOPendiente_yaNoAdmitenCambiosDelVendedor(EstadoPedido estado) {
        assertThat(estado.siguientesDelEnvio()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = EstadoPedido.class, names = {"FINALIZADO", "CANCELADO", "PENDIENTE"})
    void elVendedorNuncaPuedeLlevarElPedidoAUnEstadoFueraDelEnvio(EstadoPedido destino) {
        assertThat(EstadoPedido.PAGADO.puedeAvanzarA(destino)).isFalse();
    }

    @Test
    void losPasosDelEnvioSiguenEnCurso() {
        assertThat(List.of(EstadoPedido.PAGADO, EstadoPedido.EN_PREPARACION, EstadoPedido.ENVIADO,
                EstadoPedido.EN_REPARTO, EstadoPedido.ENTREGADO))
                .allMatch(EstadoPedido::esPasoDelEnvio)
                .allMatch(EstadoPedido::enProceso);
        assertThat(EstadoPedido.FINALIZADO.esPasoDelEnvio()).isFalse();
        assertThat(EstadoPedido.CANCELADO.enProceso()).isFalse();
    }

    @Test
    void unPedidoNuevo_seActualizoCuandoSeHizo() {
        assertThat(pedido().getFechaActualizacion()).isEqualTo(FECHA);
    }

    @Test
    void unPedidoAnteriorAGuardarLaActualizacion_usaSuFecha() {
        Pedido antiguo = pedido();
        ReflectionTestUtils.setField(antiguo, "fechaActualizacion", null);
        assertThat(antiguo.getFechaActualizacion()).isEqualTo(FECHA);
    }

    @Test
    void cancelarOFinalizar_guardaCuandoSeHizo() {
        Pedido cancelado = pedido();
        cancelado.cancelar(DESPUES);
        assertThat(cancelado.getEstado()).isEqualTo(EstadoPedido.CANCELADO);
        assertThat(cancelado.getFechaActualizacion()).isEqualTo(DESPUES);

        Pedido finalizado = pedido();
        finalizado.finalizar(DESPUES);
        assertThat(finalizado.getEstado()).isEqualTo(EstadoPedido.FINALIZADO);
        assertThat(finalizado.getFechaActualizacion()).isEqualTo(DESPUES);
    }

    @ParameterizedTest
    @EnumSource(value = EstadoPedido.class, names = {"PAGADO", "EN_PREPARACION"})
    void mientrasNoSeHaEnviado_elClientePuedeCancelarlo(EstadoPedido estado) {
        Pedido pedido = pedido();
        ReflectionTestUtils.setField(pedido, "estado", estado);
        assertThat(pedido.sePuedeCancelar()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = EstadoPedido.class, names = {"ENVIADO", "EN_REPARTO", "ENTREGADO", "FINALIZADO", "CANCELADO"})
    void unaVezEnviadoOTerminado_yaNoSePuedeCancelar(EstadoPedido estado) {
        Pedido pedido = pedido();
        ReflectionTestUtils.setField(pedido, "estado", estado);
        assertThat(pedido.sePuedeCancelar()).isFalse();
    }

    @Test
    void unPedidoNuevo_empiezaSuSeguimientoPagadoCuandoSeHizo() {
        assertThat(pedido().getSeguimiento()).singleElement().satisfies(evento -> {
            assertThat(evento.getEstado()).isEqualTo(EstadoPedido.PAGADO);
            assertThat(evento.getFecha()).isEqualTo(FECHA);
        });
    }

    @Test
    void cancelarOFinalizar_seAnotaEnElSeguimiento() {
        Pedido pedido = pedido();
        pedido.finalizar(DESPUES);
        assertThat(pedido.getSeguimiento()).extracting(EventoSeguimiento::getEstado)
                .containsExactly(EstadoPedido.PAGADO, EstadoPedido.FINALIZADO);
        assertThat(pedido.getSeguimiento().get(1).getFecha()).isEqualTo(DESPUES);
    }

    @Test
    void unPedidoAnteriorAlSeguimiento_noTieneHistorialPeroSeAnotanLosCambiosNuevos() {
        Pedido antiguo = pedido();
        ReflectionTestUtils.setField(antiguo, "seguimiento", null);
        assertThat(antiguo.getSeguimiento()).isEmpty();

        antiguo.cancelar(DESPUES);
        assertThat(antiguo.getSeguimiento()).extracting(EventoSeguimiento::getEstado)
                .containsExactly(EstadoPedido.CANCELADO);
    }

    @Test
    void losPasosDelEnvioVanEnOrden() {
        assertThat(EstadoPedido.pasosDelEnvio()).containsExactly(EstadoPedido.PAGADO, EstadoPedido.EN_PREPARACION,
                EstadoPedido.ENVIADO, EstadoPedido.EN_REPARTO, EstadoPedido.ENTREGADO);
    }

    @ParameterizedTest
    @EnumSource(value = EstadoPedido.class, names = {"PAGADO", "EN_PREPARACION", "ENVIADO", "EN_REPARTO", "ENTREGADO"})
    void elClientePuedeConfirmarLaRecepcionDuranteTodoElEnvio(EstadoPedido estado) {
        Pedido pedido = pedido();
        ReflectionTestUtils.setField(pedido, "estado", estado);
        assertThat(pedido.sePuedeConfirmarRecepcion()).isTrue();
    }

    private static Pedido pedido() {
        return new Pedido("64c000000000000000000011", "64c000000000000000000001",
                new DireccionEnvio("Calle Mayor 1", "Ciudad Real", "13001"),
                List.of(new LineaPedido("64c000000000000000000021", "Teclado", 1, 18.0)), 18.0, FECHA);
    }
}
