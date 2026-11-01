package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.util.ReflectionTestUtils;

import com.esibuy.esibuy_backend.dto.PedidoDTO;
import com.esibuy.esibuy_backend.excepcion.EstadoPedidoNoPermitidoException;
import com.esibuy.esibuy_backend.excepcion.PedidoNoEncontradoException;
import com.esibuy.esibuy_backend.modelo.DireccionEnvio;
import com.esibuy.esibuy_backend.modelo.EstadoPedido;
import com.esibuy.esibuy_backend.modelo.LineaPedido;
import com.esibuy.esibuy_backend.modelo.Pedido;
import com.esibuy.esibuy_backend.modelo.Producto;
import com.esibuy.esibuy_backend.repositorio.RepositorioCarrito;
import com.esibuy.esibuy_backend.repositorio.RepositorioPedido;
import com.esibuy.esibuy_backend.repositorio.RepositorioProducto;
import com.esibuy.esibuy_backend.repositorio.RepositorioValoracion;

/**
 * HU-28 Cancelar pedido: solo si esta pagado, devolviendo el stock. HU-30 Confirmar recepcion: el pedido pasa a
 * FINALIZADO. En los dos casos el pedido tiene que ser del cliente de la sesion.
 */
@ExtendWith(MockitoExtension.class)
class ServicioPedidoCancelarTest {

    private static final String ID_CLIENTE = "64b000000000000000000001";
    private static final String ID_OTRO_CLIENTE = "64b000000000000000000009";
    private static final String ID_TECLADO = "64b000000000000000000002";
    private static final String ID_RATON = "64b000000000000000000003";
    private static final String ID_PEDIDO = "64b000000000000000000101";
    private static final Instant AHORA = Instant.parse("2026-10-24T10:15:30Z");

    @Mock
    private RepositorioPedido repositorioPedido;
    @Mock
    private RepositorioCarrito repositorioCarrito;
    @Mock
    private RepositorioProducto repositorioProducto;
    @Mock
    private RepositorioValoracion repositorioValoracion;
    @Mock
    private MongoTemplate mongoTemplate;
    @Spy
    private Clock reloj = Clock.fixed(AHORA, ZoneOffset.UTC);
    @InjectMocks
    private ServicioPedido servicio;

    // ---------- Cancelar ----------

    @Test
    void cancelarUnPedidoPagadoLoDejaCanceladoYDevuelveElStockDeCadaLinea() {
        // Given: 2 teclados y 3 ratones
        pedidoEnBaseDeDatos(ID_CLIENTE, EstadoPedido.PAGADO);
        when(repositorioPedido.save(any())).thenAnswer(invocacion -> invocacion.getArgument(0));
        // When
        PedidoDTO pedido = servicio.cancelar(ID_CLIENTE, ID_PEDIDO);
        // Then
        assertThat(pedido.estado()).isEqualTo(EstadoPedido.CANCELADO);
        ArgumentCaptor<Query> consultas = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> cambios = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate, times(2)).updateFirst(consultas.capture(), cambios.capture(), eq(Producto.class));
        assertThat(consultas.getAllValues().get(0).getQueryObject().get("_id")).hasToString(ID_TECLADO);
        assertThat(((Document) cambios.getAllValues().get(0).getUpdateObject().get("$inc")).get("stock")).isEqualTo(2);
        assertThat(consultas.getAllValues().get(1).getQueryObject().get("_id")).hasToString(ID_RATON);
        assertThat(((Document) cambios.getAllValues().get(1).getUpdateObject().get("$inc")).get("stock")).isEqualTo(3);
    }

    @ParameterizedTest
    @EnumSource(value = EstadoPedido.class, names = {"ENVIADO", "ENTREGADO", "FINALIZADO", "CANCELADO"})
    void cancelarUnPedidoQueNoEstaPagadoLanzaExcepcionSinTocarElStock(EstadoPedido estado) {
        // Given
        pedidoEnBaseDeDatos(ID_CLIENTE, estado);
        // When / Then
        assertThatThrownBy(() -> servicio.cancelar(ID_CLIENTE, ID_PEDIDO))
                .isInstanceOf(EstadoPedidoNoPermitidoException.class)
                .hasMessage(ServicioPedido.MENSAJE_NO_CANCELABLE);
        verifyNoInteractions(mongoTemplate);
        verify(repositorioPedido, never()).save(any());
    }

    @Test
    void cancelarUnPedidoDeOtroClienteEsComoSiNoExistiera() {
        // Given
        pedidoEnBaseDeDatos(ID_OTRO_CLIENTE, EstadoPedido.PAGADO);
        // When / Then
        assertThatThrownBy(() -> servicio.cancelar(ID_CLIENTE, ID_PEDIDO))
                .isInstanceOf(PedidoNoEncontradoException.class);
        verifyNoInteractions(mongoTemplate);
    }

    @Test
    void cancelarConUnIdQueNoEsValidoLanzaNoEncontradoSinConsultar() {
        assertThatThrownBy(() -> servicio.cancelar(ID_CLIENTE, "no-es-un-id"))
                .isInstanceOf(PedidoNoEncontradoException.class);
        verifyNoInteractions(repositorioPedido);
    }

    // ---------- Confirmar recepcion ----------

    @ParameterizedTest
    @EnumSource(value = EstadoPedido.class, names = {"PAGADO", "ENVIADO", "ENTREGADO"})
    void confirmarRecepcionDeUnPedidoEnCursoLoFinaliza(EstadoPedido estado) {
        // Given
        pedidoEnBaseDeDatos(ID_CLIENTE, estado);
        when(repositorioPedido.save(any())).thenAnswer(invocacion -> invocacion.getArgument(0));
        // When
        PedidoDTO pedido = servicio.confirmarRecepcion(ID_CLIENTE, ID_PEDIDO);
        // Then
        assertThat(pedido.estado()).isEqualTo(EstadoPedido.FINALIZADO);
        verifyNoInteractions(mongoTemplate);
    }

    @ParameterizedTest
    @EnumSource(value = EstadoPedido.class, names = {"FINALIZADO", "CANCELADO"})
    void confirmarRecepcionDeUnPedidoTerminadoLanzaExcepcion(EstadoPedido estado) {
        // Given
        pedidoEnBaseDeDatos(ID_CLIENTE, estado);
        // When / Then
        assertThatThrownBy(() -> servicio.confirmarRecepcion(ID_CLIENTE, ID_PEDIDO))
                .isInstanceOf(EstadoPedidoNoPermitidoException.class)
                .hasMessage(ServicioPedido.MENSAJE_NO_CONFIRMABLE);
        verify(repositorioPedido, never()).save(any());
    }

    @Test
    void confirmarRecepcionDeUnPedidoInexistenteLanzaNoEncontrado() {
        // Given
        when(repositorioPedido.findById(ID_PEDIDO)).thenReturn(Optional.empty());
        // When / Then
        assertThatThrownBy(() -> servicio.confirmarRecepcion(ID_CLIENTE, ID_PEDIDO))
                .isInstanceOf(PedidoNoEncontradoException.class);
    }

    private void pedidoEnBaseDeDatos(String idCliente, EstadoPedido estado) {
        Pedido pedido = new Pedido(idCliente, new DireccionEnvio("Calle Mayor 1", "Ciudad Real", "13001"),
                List.of(new LineaPedido(ID_TECLADO, "Teclado", 2, 18.0), new LineaPedido(ID_RATON, "Ratón", 3, 5.5)),
                52.5, AHORA);
        ReflectionTestUtils.setField(pedido, "id", ID_PEDIDO);
        ReflectionTestUtils.setField(pedido, "estado", estado);
        when(repositorioPedido.findById(ID_PEDIDO)).thenReturn(Optional.of(pedido));
    }
}
