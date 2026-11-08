package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.esibuy.esibuy_backend.dto.DireccionEnvioDTO;
import com.esibuy.esibuy_backend.dto.PagarDTO;
import com.esibuy.esibuy_backend.dto.PedidoDTO;
import com.esibuy.esibuy_backend.dto.ResultadoPagoDTO;
import com.esibuy.esibuy_backend.excepcion.MetodoPagoInvalidoException;
import com.esibuy.esibuy_backend.excepcion.MetodoPagoNoEncontradoException;
import com.esibuy.esibuy_backend.excepcion.PedidoInvalidoException;
import com.esibuy.esibuy_backend.modelo.EstadoPedido;
import com.esibuy.esibuy_backend.modelo.MetodoPago;
import com.esibuy.esibuy_backend.modelo.Pago;
import com.esibuy.esibuy_backend.repositorio.RepositorioMetodoPago;
import com.esibuy.esibuy_backend.repositorio.RepositorioPago;

/** HU-23 Pago (ficticio): tarjeta propia y no caducada, pedido de un vendedor creado con ServicioPedido y justificante. */
@ExtendWith(MockitoExtension.class)
class ServicioPagoTest {

    private static final String ID_CLIENTE = "64b000000000000000000001";
    private static final String ID_TARJETA = "64b000000000000000000050";
    private static final String ID_PEDIDO = "64b000000000000000000090";
    private static final String ID_VENDEDOR = "64b0000000000000000000a1";
    // "Hoy" fijo en los tests: 25 de octubre de 2026
    private static final Instant AHORA = Instant.parse("2026-10-25T12:00:00Z");
    private static final Clock RELOJ = Clock.fixed(AHORA, ZoneOffset.UTC);
    private static final DireccionEnvioDTO DIRECCION = new DireccionEnvioDTO("Calle Mayor 1", "Ciudad Real", "13001");
    private static final PedidoDTO PEDIDO =
            new PedidoDTO(ID_PEDIDO, EstadoPedido.PAGADO, AHORA, 52.5, DIRECCION, List.of());

    @Mock
    private ServicioPedido servicioPedido;
    @Mock
    private RepositorioMetodoPago repositorioMetodoPago;
    @Mock
    private RepositorioPago repositorioPago;
    private ServicioPago servicio;

    @BeforeEach
    void crearServicio() {
        servicio = new ServicioPago(servicioPedido, repositorioMetodoPago, repositorioPago, RELOJ);
    }

    @Test
    void pagarConUnaTarjetaPropiaCreaElPedidoYGuardaElJustificante() {
        // Given
        tarjetaDelCliente("12/28");
        when(servicioPedido.realizar(ID_CLIENTE, false, DIRECCION, null)).thenReturn(PEDIDO);
        // When
        ResultadoPagoDTO resultado = servicio.pagar(ID_CLIENTE, false, new PagarDTO(ID_TARJETA, DIRECCION));
        // Then
        assertThat(resultado.pedido().estado()).isEqualTo(EstadoPedido.PAGADO);
        assertThat(resultado.ultimosDigitos()).isEqualTo("4242");
        ArgumentCaptor<Pago> captor = ArgumentCaptor.forClass(Pago.class);
        verify(repositorioPago).save(captor.capture());
        Pago pago = captor.getValue();
        assertThat(pago.getIdPedido()).isEqualTo(ID_PEDIDO);
        assertThat(pago.getIdCliente()).isEqualTo(ID_CLIENTE);
        assertThat(pago.getUltimosDigitos()).isEqualTo("4242");
        assertThat(pago.getImporte()).isEqualTo(52.5);
        assertThat(pago.getFecha()).isEqualTo(AHORA);
    }

    @Test
    void pagarDeUnVendedorPideElPedidoSoloDeEseVendedor() {
        // Given
        tarjetaDelCliente("12/28");
        when(servicioPedido.realizar(ID_CLIENTE, false, DIRECCION, ID_VENDEDOR)).thenReturn(PEDIDO);
        // When
        servicio.pagar(ID_CLIENTE, false, new PagarDTO(ID_TARJETA, DIRECCION, ID_VENDEDOR));
        // Then
        verify(servicioPedido).realizar(ID_CLIENTE, false, DIRECCION, ID_VENDEDOR);
    }

    @Test
    void pagarComoPremiumPideElPedidoConDescuentoPremium() {
        // Given
        tarjetaDelCliente("12/28");
        when(servicioPedido.realizar(ID_CLIENTE, true, DIRECCION, null)).thenReturn(PEDIDO);
        // When
        servicio.pagar(ID_CLIENTE, true, new PagarDTO(ID_TARJETA, DIRECCION));
        // Then
        verify(servicioPedido).realizar(ID_CLIENTE, true, DIRECCION, null);
    }

    @Test
    void pagarConUnaTarjetaQueCaducaEsteMesSeAcepta() {
        // Given: hoy es octubre de 2026 y "10/26" vale hasta el 31 de octubre
        tarjetaDelCliente("10/26");
        when(servicioPedido.realizar(ID_CLIENTE, false, DIRECCION, null)).thenReturn(PEDIDO);
        // When
        ResultadoPagoDTO resultado = servicio.pagar(ID_CLIENTE, false, new PagarDTO(ID_TARJETA, DIRECCION));
        // Then
        assertThat(resultado.pedido().id()).isEqualTo(ID_PEDIDO);
    }

    @Test
    void pagarConUnaTarjetaCaducadaLanzaInvalidoYNoCreaElPedido() {
        // Given
        tarjetaDelCliente("09/26");
        // When / Then
        assertThatThrownBy(() -> servicio.pagar(ID_CLIENTE, false, new PagarDTO(ID_TARJETA, DIRECCION)))
                .isInstanceOf(MetodoPagoInvalidoException.class);
        verifyNoInteractions(servicioPedido, repositorioPago);
    }

    @Test
    void pagarConUnaTarjetaAjenaOInexistenteLanzaNoEncontradoYNoCreaElPedido() {
        // Given: la consulta exige que sea del cliente, asi que la de otro no aparece
        when(repositorioMetodoPago.buscarDelCliente(new ObjectId(ID_TARJETA), new ObjectId(ID_CLIENTE)))
                .thenReturn(Optional.empty());
        // When / Then
        assertThatThrownBy(() -> servicio.pagar(ID_CLIENTE, false, new PagarDTO(ID_TARJETA, DIRECCION)))
                .isInstanceOf(MetodoPagoNoEncontradoException.class);
        verifyNoInteractions(servicioPedido, repositorioPago);
    }

    @Test
    void pagarConIdDeTarjetaMalFormadoLanzaNoEncontradoSinConsultar() {
        assertThatThrownBy(() -> servicio.pagar(ID_CLIENTE, false, new PagarDTO("no-es-un-id", DIRECCION)))
                .isInstanceOf(MetodoPagoNoEncontradoException.class);
        verifyNoInteractions(repositorioMetodoPago, servicioPedido, repositorioPago);
    }

    @Test
    void pagarSinDatosLanzaNoEncontrado() {
        assertThatThrownBy(() -> servicio.pagar(ID_CLIENTE, false, null))
                .isInstanceOf(MetodoPagoNoEncontradoException.class);
        verifyNoInteractions(repositorioMetodoPago, servicioPedido, repositorioPago);
    }

    @Test
    void siNoSePuedeCrearElPedidoNoSeGuardaElJustificante() {
        // Given: por ejemplo, el carrito tiene varios vendedores y no se ha elegido ninguno
        tarjetaDelCliente("12/28");
        when(servicioPedido.realizar(ID_CLIENTE, false, DIRECCION, null))
                .thenThrow(new PedidoInvalidoException("Elige de que vendedor quieres hacer la compra"));
        // When / Then
        assertThatThrownBy(() -> servicio.pagar(ID_CLIENTE, false, new PagarDTO(ID_TARJETA, DIRECCION)))
                .isInstanceOf(PedidoInvalidoException.class);
        verify(repositorioPago, never()).save(any());
    }

    // ---------- Ayudas ----------

    private void tarjetaDelCliente(String caducidad) {
        MetodoPago tarjeta = new MetodoPago(ID_CLIENTE, "Carlos Ruiz", "4242", caducidad, AHORA);
        when(repositorioMetodoPago.buscarDelCliente(new ObjectId(ID_TARJETA), new ObjectId(ID_CLIENTE)))
                .thenReturn(Optional.of(tarjeta));
    }
}