package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import com.esibuy.esibuy_backend.excepcion.EstadoPedidoNoPermitidoException;
import com.esibuy.esibuy_backend.excepcion.PedidoNoEncontradoException;
import com.esibuy.esibuy_backend.excepcion.ProductoNoEnPedidoException;
import com.esibuy.esibuy_backend.excepcion.ProductoYaValoradoException;
import com.esibuy.esibuy_backend.excepcion.ValoracionInvalidaException;
import com.esibuy.esibuy_backend.modelo.DireccionEnvio;
import com.esibuy.esibuy_backend.modelo.EstadoPedido;
import com.esibuy.esibuy_backend.modelo.LineaPedido;
import com.esibuy.esibuy_backend.modelo.Pedido;
import com.esibuy.esibuy_backend.modelo.Valoracion;
import com.esibuy.esibuy_backend.repositorio.RepositorioValoracion;

/** HU-30 Valorar producto: solo productos de pedidos finalizados del cliente, una vez y de 1 a 5 estrellas. */
@ExtendWith(MockitoExtension.class)
class ServicioValoracionTest {

    private static final String ID_CLIENTE = "64b000000000000000000001";
    private static final String ID_TECLADO = "64b000000000000000000002";
    private static final String ID_RATON = "64b000000000000000000003";
    private static final String ID_PEDIDO = "64b000000000000000000101";

    @Mock
    private RepositorioValoracion repositorioValoracion;
    @Mock
    private ServicioPedido servicioPedido;
    @InjectMocks
    private ServicioValoracion servicio;

    @Test
    void valorarUnProductoDeUnPedidoFinalizadoGuardaLaValoracion() {
        // Given
        pedidoDelCliente(EstadoPedido.FINALIZADO);
        // When
        servicio.valorar(ID_CLIENTE, ID_PEDIDO, ID_TECLADO, 4);
        // Then
        ArgumentCaptor<Valoracion> captor = ArgumentCaptor.forClass(Valoracion.class);
        verify(repositorioValoracion).save(captor.capture());
        assertThat(captor.getValue().getIdCliente()).isEqualTo(ID_CLIENTE);
        assertThat(captor.getValue().getIdPedido()).isEqualTo(ID_PEDIDO);
        assertThat(captor.getValue().getIdProducto()).isEqualTo(ID_TECLADO);
        assertThat(captor.getValue().getPuntos()).isEqualTo(4);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, 6, -1})
    void valorarConPuntosFueraDe1A5LanzaExcepcionSinConsultarNada(Integer puntos) {
        assertThatThrownBy(() -> servicio.valorar(ID_CLIENTE, ID_PEDIDO, ID_TECLADO, puntos))
                .isInstanceOf(ValoracionInvalidaException.class);
        verifyNoInteractions(servicioPedido, repositorioValoracion);
    }

    @ParameterizedTest
    @EnumSource(value = EstadoPedido.class, names = {"PAGADO", "ENVIADO", "ENTREGADO", "CANCELADO"})
    void valorarUnProductoDeUnPedidoNoFinalizadoLanzaExcepcion(EstadoPedido estado) {
        // Given
        pedidoDelCliente(estado);
        // When / Then
        assertThatThrownBy(() -> servicio.valorar(ID_CLIENTE, ID_PEDIDO, ID_TECLADO, 5))
                .isInstanceOf(EstadoPedidoNoPermitidoException.class)
                .hasMessage(ServicioValoracion.MENSAJE_PEDIDO_NO_FINALIZADO);
        verify(repositorioValoracion, never()).save(any());
    }

    @Test
    void valorarUnProductoQueNoEstaEnElPedidoLanzaExcepcion() {
        // Given
        pedidoDelCliente(EstadoPedido.FINALIZADO);
        // When / Then
        assertThatThrownBy(() -> servicio.valorar(ID_CLIENTE, ID_PEDIDO, ID_RATON, 5))
                .isInstanceOf(ProductoNoEnPedidoException.class);
        verify(repositorioValoracion, never()).save(any());
    }

    @Test
    void valorarDosVecesElMismoProductoDelMismoPedidoLanzaExcepcion() {
        // Given
        pedidoDelCliente(EstadoPedido.FINALIZADO);
        when(repositorioValoracion.existe(new ObjectId(ID_CLIENTE), new ObjectId(ID_PEDIDO), new ObjectId(ID_TECLADO)))
                .thenReturn(true);
        // When / Then
        assertThatThrownBy(() -> servicio.valorar(ID_CLIENTE, ID_PEDIDO, ID_TECLADO, 2))
                .isInstanceOf(ProductoYaValoradoException.class);
        verify(repositorioValoracion, never()).save(any());
    }

    @Test
    void siLaBaseDeDatosRechazaElDuplicadoTambienEsYaValorado() {
        // Given: dos peticiones a la vez pasan la comprobacion y el indice unico para la segunda
        pedidoDelCliente(EstadoPedido.FINALIZADO);
        when(repositorioValoracion.save(any())).thenThrow(new DuplicateKeyException("duplicado"));
        // When / Then
        assertThatThrownBy(() -> servicio.valorar(ID_CLIENTE, ID_PEDIDO, ID_TECLADO, 3))
                .isInstanceOf(ProductoYaValoradoException.class);
    }

    @Test
    void valorarUnPedidoQueNoEsDelClienteLanzaNoEncontrado() {
        // Given
        when(servicioPedido.pedidoDelCliente(ID_CLIENTE, ID_PEDIDO)).thenThrow(new PedidoNoEncontradoException());
        // When / Then
        assertThatThrownBy(() -> servicio.valorar(ID_CLIENTE, ID_PEDIDO, ID_TECLADO, 3))
                .isInstanceOf(PedidoNoEncontradoException.class);
        verifyNoInteractions(repositorioValoracion);
    }

    private void pedidoDelCliente(EstadoPedido estado) {
        Pedido pedido = new Pedido(ID_CLIENTE, new DireccionEnvio("Calle Mayor 1", "Ciudad Real", "13001"),
                List.of(new LineaPedido(ID_TECLADO, "Teclado", 2, 18.0)), 36.0, Instant.parse("2026-10-24T10:15:30Z"));
        ReflectionTestUtils.setField(pedido, "id", ID_PEDIDO);
        ReflectionTestUtils.setField(pedido, "estado", estado);
        when(servicioPedido.pedidoDelCliente(ID_CLIENTE, ID_PEDIDO)).thenReturn(pedido);
    }
}
