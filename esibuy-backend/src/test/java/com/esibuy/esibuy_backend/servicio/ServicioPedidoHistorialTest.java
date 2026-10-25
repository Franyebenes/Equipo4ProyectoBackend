package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import com.esibuy.esibuy_backend.dto.HistorialPedidosDTO;
import com.esibuy.esibuy_backend.dto.PedidoDTO;
import com.esibuy.esibuy_backend.modelo.DireccionEnvio;
import com.esibuy.esibuy_backend.modelo.EstadoPedido;
import com.esibuy.esibuy_backend.modelo.LineaPedido;
import com.esibuy.esibuy_backend.modelo.Pedido;
import com.esibuy.esibuy_backend.modelo.Valoracion;
import com.esibuy.esibuy_backend.repositorio.RepositorioCarrito;
import com.esibuy.esibuy_backend.repositorio.RepositorioPedido;
import com.esibuy.esibuy_backend.repositorio.RepositorioProducto;
import com.esibuy.esibuy_backend.repositorio.RepositorioValoracion;

/** HU-27 Historial de compras: pedidos en proceso e historicos, con la valoracion de cada producto si la hay. */
@ExtendWith(MockitoExtension.class)
class ServicioPedidoHistorialTest {

    private static final String ID_CLIENTE = "64b000000000000000000001";
    private static final String ID_TECLADO = "64b000000000000000000002";
    private static final String ID_PEDIDO_PAGADO = "64b000000000000000000101";
    private static final String ID_PEDIDO_ENVIADO = "64b000000000000000000102";
    private static final String ID_PEDIDO_FINALIZADO = "64b000000000000000000103";
    private static final String ID_PEDIDO_CANCELADO = "64b000000000000000000104";
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

    @Test
    void sinPedidosDevuelveLasDosListasVacias() {
        // Given
        when(repositorioPedido.buscarPorCliente(any(ObjectId.class), any())).thenReturn(List.of());
        // When
        HistorialPedidosDTO historial = servicio.historial(ID_CLIENTE);
        // Then
        assertThat(historial.enProceso()).isEmpty();
        assertThat(historial.historicos()).isEmpty();
    }

    @Test
    void separaLosPedidosEnCursoDeLosTerminadosRespetandoElOrden() {
        // Given: el repositorio ya los devuelve del mas reciente al mas antiguo
        when(repositorioPedido.buscarPorCliente(any(ObjectId.class), any())).thenReturn(List.of(
                pedido(ID_PEDIDO_FINALIZADO, EstadoPedido.FINALIZADO),
                pedido(ID_PEDIDO_ENVIADO, EstadoPedido.ENVIADO),
                pedido(ID_PEDIDO_CANCELADO, EstadoPedido.CANCELADO),
                pedido(ID_PEDIDO_PAGADO, EstadoPedido.PAGADO)));
        // When
        HistorialPedidosDTO historial = servicio.historial(ID_CLIENTE);
        // Then
        assertThat(historial.enProceso()).extracting(PedidoDTO::id)
                .containsExactly(ID_PEDIDO_ENVIADO, ID_PEDIDO_PAGADO);
        assertThat(historial.historicos()).extracting(PedidoDTO::id)
                .containsExactly(ID_PEDIDO_FINALIZADO, ID_PEDIDO_CANCELADO);
    }

    @Test
    void cadaLineaLlevaLaValoracionQueDioElClienteEnEsePedido() {
        // Given: el teclado se valoro en el pedido finalizado, no en el pagado
        when(repositorioPedido.buscarPorCliente(any(ObjectId.class), any())).thenReturn(List.of(
                pedido(ID_PEDIDO_FINALIZADO, EstadoPedido.FINALIZADO),
                pedido(ID_PEDIDO_PAGADO, EstadoPedido.PAGADO)));
        when(repositorioValoracion.buscarPorCliente(new ObjectId(ID_CLIENTE)))
                .thenReturn(List.of(new Valoracion(ID_TECLADO, ID_CLIENTE, ID_PEDIDO_FINALIZADO, 4)));
        // When
        HistorialPedidosDTO historial = servicio.historial(ID_CLIENTE);
        // Then
        assertThat(historial.historicos().get(0).lineas().get(0).valoracion()).isEqualTo(4);
        assertThat(historial.enProceso().get(0).lineas().get(0).valoracion()).isNull();
    }

    @Test
    void elPedidoSeDevuelveConSusDatosYElSubtotalDeCadaLinea() {
        // Given
        when(repositorioPedido.buscarPorCliente(any(ObjectId.class), any()))
                .thenReturn(List.of(pedido(ID_PEDIDO_PAGADO, EstadoPedido.PAGADO)));
        // When
        PedidoDTO pedido = servicio.historial(ID_CLIENTE).enProceso().get(0);
        // Then
        assertThat(pedido.fecha()).isEqualTo(AHORA);
        assertThat(pedido.precioTotal()).isEqualTo(36.0);
        assertThat(pedido.direccionEnvio().calle()).isEqualTo("Calle Mayor 1");
        assertThat(pedido.lineas().get(0).nombre()).isEqualTo("Teclado");
        assertThat(pedido.lineas().get(0).cantidad()).isEqualTo(2);
        assertThat(pedido.lineas().get(0).subtotal()).isEqualTo(36.0);
    }

    private static Pedido pedido(String id, EstadoPedido estado) {
        Pedido pedido = new Pedido(ID_CLIENTE, new DireccionEnvio("Calle Mayor 1", "Ciudad Real", "13001"),
                List.of(new LineaPedido(ID_TECLADO, "Teclado", 2, 18.0)), 36.0, AHORA);
        ReflectionTestUtils.setField(pedido, "id", id);
        ReflectionTestUtils.setField(pedido, "estado", estado);
        return pedido;
    }
}
