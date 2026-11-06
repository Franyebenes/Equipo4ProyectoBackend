package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.esibuy.esibuy_backend.dto.PasoSeguimientoDTO;
import com.esibuy.esibuy_backend.dto.SeguimientoPedidoDTO;
import com.esibuy.esibuy_backend.excepcion.PedidoNoEncontradoException;
import com.esibuy.esibuy_backend.modelo.DireccionEnvio;
import com.esibuy.esibuy_backend.modelo.EstadoPedido;
import com.esibuy.esibuy_backend.modelo.EventoSeguimiento;
import com.esibuy.esibuy_backend.modelo.LineaPedido;
import com.esibuy.esibuy_backend.modelo.Pedido;
import com.esibuy.esibuy_backend.repositorio.RepositorioPedido;

/**
 * Estado del envio: el cliente consulta el seguimiento de sus pedidos (estado mas reciente, pasos del envio con su
 * fecha y fecha estimada de entrega). Solo los suyos, y con un aviso cuando no hay seguimiento que mostrar.
 */
@ExtendWith(MockitoExtension.class)
class ServicioSeguimientoPedidoTest {

    private static final String ID_CLIENTE = "64c000000000000000000011";
    private static final String ID_OTRO_CLIENTE = "64c000000000000000000012";
    private static final String ID_VENDEDOR = "64c000000000000000000001";
    private static final String ID_PEDIDO = "64c000000000000000000101";
    private static final Instant FECHA = Instant.parse("2026-11-02T09:30:00Z");
    private static final Instant PREPARADO = Instant.parse("2026-11-02T16:00:00Z");
    private static final Instant ENVIADO = Instant.parse("2026-11-03T10:00:00Z");
    private static final Instant ENTREGADO = Instant.parse("2026-11-05T12:00:00Z");
    private static final Instant RECIBIDO = Instant.parse("2026-11-05T20:00:00Z");

    @Mock
    private RepositorioPedido repositorioPedido;
    @Spy
    private CalculadoraEntrega calculadoraEntrega = new CalculadoraEntrega(5);
    @InjectMocks
    private ServicioSeguimientoPedido servicio;

    @Test
    void pedidoRecienHecho_estaPagadoConElRestoDePasosPendientesYFechaEstimada() {
        // Given
        when(repositorioPedido.findById(ID_PEDIDO)).thenReturn(Optional.of(pedido(ID_CLIENTE)));
        // When
        SeguimientoPedidoDTO seguimiento = servicio.consultar(ID_CLIENTE, ID_PEDIDO);
        // Then
        assertThat(seguimiento.disponible()).isTrue();
        assertThat(seguimiento.estado()).isEqualTo(EstadoPedido.PAGADO);
        assertThat(seguimiento.fechaActualizacion()).isEqualTo(FECHA);
        assertThat(seguimiento.fechaEntregaEstimada()).isEqualTo(LocalDate.of(2026, 11, 7));
        assertThat(seguimiento.fechaEntrega()).isNull();
        assertThat(seguimiento.pasos()).extracting(PasoSeguimientoDTO::estado).containsExactly(EstadoPedido.PAGADO,
                EstadoPedido.EN_PREPARACION, EstadoPedido.ENVIADO, EstadoPedido.EN_REPARTO, EstadoPedido.ENTREGADO);
        assertThat(seguimiento.pasos()).extracting(PasoSeguimientoDTO::completado)
                .containsExactly(true, false, false, false, false);
        assertThat(seguimiento.pasos().get(0).actual()).isTrue();
        assertThat(seguimiento.pasos().get(0).fecha()).isEqualTo(FECHA);
        assertThat(seguimiento.pasos().get(1).fecha()).isNull();
    }

    @Test
    void pedidoEnviado_muestraCadaPasoConLaFechaEnQueSeAlcanzo() {
        // Given
        Pedido pedido = pedido(ID_CLIENTE);
        avanzar(pedido, EstadoPedido.EN_PREPARACION, PREPARADO);
        avanzar(pedido, EstadoPedido.ENVIADO, ENVIADO);
        when(repositorioPedido.findById(ID_PEDIDO)).thenReturn(Optional.of(pedido));
        // When
        SeguimientoPedidoDTO seguimiento = servicio.consultar(ID_CLIENTE, ID_PEDIDO);
        // Then
        assertThat(seguimiento.estado()).isEqualTo(EstadoPedido.ENVIADO);
        assertThat(seguimiento.fechaActualizacion()).isEqualTo(ENVIADO);
        assertThat(seguimiento.pasos()).extracting(PasoSeguimientoDTO::fecha)
                .containsExactly(FECHA, PREPARADO, ENVIADO, null, null);
        assertThat(seguimiento.pasos()).extracting(PasoSeguimientoDTO::actual)
                .containsExactly(false, false, true, false, false);
    }

    @Test
    void pedidoRecibido_tieneTodosLosPasosCompletadosYLaFechaDeEntregaSinEstimacion() {
        // Given: entregado por el vendedor y confirmado despues por el cliente
        Pedido pedido = pedido(ID_CLIENTE);
        avanzar(pedido, EstadoPedido.ENVIADO, ENVIADO);
        avanzar(pedido, EstadoPedido.ENTREGADO, ENTREGADO);
        pedido.finalizar(RECIBIDO);
        when(repositorioPedido.findById(ID_PEDIDO)).thenReturn(Optional.of(pedido));
        // When
        SeguimientoPedidoDTO seguimiento = servicio.consultar(ID_CLIENTE, ID_PEDIDO);
        // Then
        assertThat(seguimiento.estado()).isEqualTo(EstadoPedido.FINALIZADO);
        assertThat(seguimiento.pasos()).allMatch(PasoSeguimientoDTO::completado).noneMatch(PasoSeguimientoDTO::actual);
        assertThat(seguimiento.fechaEntrega()).isEqualTo(ENTREGADO);
        assertThat(seguimiento.fechaEntregaEstimada()).isNull();
    }

    @Test
    void pedidoRecibidoSinMarcarseComoEntregado_seEntregoCuandoElClienteLoConfirmo() {
        // Given
        Pedido pedido = pedido(ID_CLIENTE);
        pedido.finalizar(RECIBIDO);
        when(repositorioPedido.findById(ID_PEDIDO)).thenReturn(Optional.of(pedido));
        // When / Then
        assertThat(servicio.consultar(ID_CLIENTE, ID_PEDIDO).fechaEntrega()).isEqualTo(RECIBIDO);
    }

    @Test
    void pedidoCancelado_noTieneSeguimientoQueMostrar() {
        // Given
        Pedido pedido = pedido(ID_CLIENTE);
        pedido.cancelar(PREPARADO);
        when(repositorioPedido.findById(ID_PEDIDO)).thenReturn(Optional.of(pedido));
        // When
        SeguimientoPedidoDTO seguimiento = servicio.consultar(ID_CLIENTE, ID_PEDIDO);
        // Then
        assertThat(seguimiento.disponible()).isFalse();
        assertThat(seguimiento.pasos()).isEmpty();
        assertThat(seguimiento.estado()).isEqualTo(EstadoPedido.CANCELADO);
        assertThat(seguimiento.fechaActualizacion()).isEqualTo(PREPARADO);
        assertThat(seguimiento.fechaEntregaEstimada()).isNull();
    }

    @Test
    void pedidoAntiguoSinSeguimiento_usaLaFechaDelPedidoYLaDeSuUltimoCambio() {
        // Given: un pedido guardado antes de existir el seguimiento, que el vendedor ya ha enviado
        Pedido pedido = pedido(ID_CLIENTE);
        ReflectionTestUtils.setField(pedido, "seguimiento", null);
        ReflectionTestUtils.setField(pedido, "estado", EstadoPedido.ENVIADO);
        ReflectionTestUtils.setField(pedido, "fechaActualizacion", ENVIADO);
        when(repositorioPedido.findById(ID_PEDIDO)).thenReturn(Optional.of(pedido));
        // When
        SeguimientoPedidoDTO seguimiento = servicio.consultar(ID_CLIENTE, ID_PEDIDO);
        // Then: del paso intermedio no se sabe la fecha
        assertThat(seguimiento.disponible()).isTrue();
        assertThat(seguimiento.pasos()).extracting(PasoSeguimientoDTO::fecha)
                .containsExactly(FECHA, null, ENVIADO, null, null);
        assertThat(seguimiento.pasos()).extracting(PasoSeguimientoDTO::completado)
                .containsExactly(true, true, true, false, false);
    }

    @Test
    void pedidoDeOtroCliente_lanzaNoEncontrado() {
        // Given
        when(repositorioPedido.findById(ID_PEDIDO)).thenReturn(Optional.of(pedido(ID_OTRO_CLIENTE)));
        // When / Then
        assertThatThrownBy(() -> servicio.consultar(ID_CLIENTE, ID_PEDIDO))
                .isInstanceOf(PedidoNoEncontradoException.class);
    }

    @Test
    void pedidoInexistente_lanzaNoEncontrado() {
        // Given
        when(repositorioPedido.findById(ID_PEDIDO)).thenReturn(Optional.empty());
        // When / Then
        assertThatThrownBy(() -> servicio.consultar(ID_CLIENTE, ID_PEDIDO))
                .isInstanceOf(PedidoNoEncontradoException.class);
    }

    @Test
    void idNoValido_lanzaNoEncontradoSinConsultarLaBaseDeDatos() {
        // When / Then
        assertThatThrownBy(() -> servicio.consultar(ID_CLIENTE, "no-es-un-id"))
                .isInstanceOf(PedidoNoEncontradoException.class);
        verify(repositorioPedido, never()).findById(anyString());
    }

    private static Pedido pedido(String idCliente) {
        Pedido pedido = new Pedido(idCliente, ID_VENDEDOR, new DireccionEnvio("Calle Mayor 1", "Ciudad Real", "13001"),
                List.of(new LineaPedido("64c000000000000000000021", "Teclado", 1, 18.0)), 18.0, FECHA);
        ReflectionTestUtils.setField(pedido, "id", ID_PEDIDO);
        return pedido;
    }

    // Lo que hace el vendedor al avanzar el envio (en la base de datos lo hace ServicioPedidoVendedor)
    @SuppressWarnings("unchecked")
    private static void avanzar(Pedido pedido, EstadoPedido estado, Instant cuando) {
        ReflectionTestUtils.setField(pedido, "estado", estado);
        ReflectionTestUtils.setField(pedido, "fechaActualizacion", cuando);
        List<EventoSeguimiento> seguimiento = new ArrayList<>(
                (List<EventoSeguimiento>) ReflectionTestUtils.getField(pedido, "seguimiento"));
        seguimiento.add(new EventoSeguimiento(estado, cuando));
        ReflectionTestUtils.setField(pedido, "seguimiento", seguimiento);
    }
}
