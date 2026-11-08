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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;

import com.esibuy.esibuy_backend.dto.DireccionEnvioDTO;
import com.esibuy.esibuy_backend.dto.PedidoDTO;
import com.esibuy.esibuy_backend.excepcion.PedidoInvalidoException;
import com.esibuy.esibuy_backend.modelo.Carrito;
import com.esibuy.esibuy_backend.modelo.Pedido;
import com.esibuy.esibuy_backend.modelo.PrecioProducto;
import com.esibuy.esibuy_backend.modelo.Producto;
import com.esibuy.esibuy_backend.repositorio.RepositorioCarrito;
import com.esibuy.esibuy_backend.repositorio.RepositorioPedido;
import com.esibuy.esibuy_backend.repositorio.RepositorioProducto;
import com.esibuy.esibuy_backend.repositorio.RepositorioValoracion;

/**
 * Cada compra es de un solo vendedor (cambio pedido por el product owner): el cliente elige de que vendedor compra,
 * el pedido lleva ese vendedor y los productos de los demas se quedan en el carrito.
 */
@ExtendWith(MockitoExtension.class)
class ServicioPedidoPorVendedorTest {

    private static final String ID_CLIENTE = "64b000000000000000000001";
    private static final String ID_TECLADO = "64b000000000000000000002";
    private static final String ID_RATON = "64b000000000000000000003";
    private static final String VENDEDOR_A = "64b0000000000000000000a1";
    private static final String VENDEDOR_B = "64b0000000000000000000b2";
    private static final String VENDEDOR_SIN_PRODUCTOS = "64b0000000000000000000c3";
    private static final Instant AHORA = Instant.parse("2026-10-26T10:00:00Z");
    private static final DireccionEnvioDTO DIRECCION = new DireccionEnvioDTO("Calle Mayor 1", "Ciudad Real", "13001");

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
    void comprarDeUnVendedorSoloCompraSusProductosYDejaElRestoEnElCarrito() {
        // Given: teclado (vendedor A) x2 y raton (vendedor B) x3
        Carrito carrito = carritoConTecladoYRaton();
        productos(VENDEDOR_A, VENDEDOR_B, true);
        when(repositorioPedido.save(any())).thenAnswer(invocacion -> invocacion.getArgument(0));
        // When
        PedidoDTO pedido = servicio.realizar(ID_CLIENTE, false, DIRECCION, VENDEDOR_A);
        // Then: solo el teclado, 18 x 2 = 36
        assertThat(pedido.lineas()).hasSize(1);
        assertThat(pedido.lineas().get(0).nombre()).isEqualTo("Teclado");
        assertThat(pedido.precioTotal()).isEqualTo(36.0);
        assertThat(pedidoGuardado().getIdVendedor()).isEqualTo(VENDEDOR_A);
        // El raton sigue en el carrito para otra compra
        assertThat(carrito.getLineas()).hasSize(1);
        assertThat(carrito.getLineas().get(0).getIdProducto()).isEqualTo(ID_RATON);
        verify(repositorioCarrito).save(carrito);
    }

    @Test
    void sinElegirVendedorConVariosVendedoresPideElegirYNoGuardaNada() {
        // Given
        carritoConTecladoYRaton();
        productos(VENDEDOR_A, VENDEDOR_B, true);
        // When / Then
        assertThatThrownBy(() -> servicio.realizar(ID_CLIENTE, false, DIRECCION))
                .isInstanceOf(PedidoInvalidoException.class)
                .hasMessage(ServicioPedido.MENSAJE_VARIOS_VENDEDORES);
        verify(repositorioPedido, never()).save(any());
        verify(repositorioCarrito, never()).save(any());
    }

    @Test
    void sinElegirVendedorConUnSoloVendedorCompraTodoElCarrito() {
        // Given: los dos productos son del vendedor A
        Carrito carrito = carritoConTecladoYRaton();
        productos(VENDEDOR_A, VENDEDOR_A, true);
        when(repositorioPedido.save(any())).thenAnswer(invocacion -> invocacion.getArgument(0));
        // When
        PedidoDTO pedido = servicio.realizar(ID_CLIENTE, false, DIRECCION);
        // Then: 18 x 2 + 5.5 x 3 = 52.5
        assertThat(pedido.lineas()).hasSize(2);
        assertThat(pedido.precioTotal()).isEqualTo(52.5);
        assertThat(pedidoGuardado().getIdVendedor()).isEqualTo(VENDEDOR_A);
        assertThat(carrito.getLineas()).isEmpty();
    }

    @Test
    void comprarDeUnVendedorSinProductosEnElCarritoLanzaExcepcion() {
        // Given
        carritoConTecladoYRaton();
        productos(VENDEDOR_A, VENDEDOR_B, true);
        // When / Then
        assertThatThrownBy(() -> servicio.realizar(ID_CLIENTE, false, DIRECCION, VENDEDOR_SIN_PRODUCTOS))
                .isInstanceOf(PedidoInvalidoException.class)
                .hasMessage(ServicioPedido.MENSAJE_SIN_PRODUCTOS_VENDEDOR);
        verify(repositorioPedido, never()).save(any());
    }

    @Test
    void unProductoDesactivadoDeOtroVendedorNoImpideComprar() {
        // Given: el raton (vendedor B) esta desactivado, pero se compra lo del vendedor A
        carritoConTecladoYRaton();
        productos(VENDEDOR_A, VENDEDOR_B, false);
        when(repositorioPedido.save(any())).thenAnswer(invocacion -> invocacion.getArgument(0));
        // When
        PedidoDTO pedido = servicio.realizar(ID_CLIENTE, false, DIRECCION, VENDEDOR_A);
        // Then
        assertThat(pedido.lineas()).hasSize(1);
        assertThat(pedidoGuardado().getIdVendedor()).isEqualTo(VENDEDOR_A);
    }

    @Test
    void idDeVendedorMalFormadoLanzaExcepcionSinConsultarElCarrito() {
        assertThatThrownBy(() -> servicio.realizar(ID_CLIENTE, false, DIRECCION, "no-es-un-id"))
                .isInstanceOf(PedidoInvalidoException.class)
                .hasMessage(ServicioPedido.MENSAJE_SIN_PRODUCTOS_VENDEDOR);
        verifyNoInteractions(repositorioCarrito, repositorioPedido);
    }

    // ---------- Ayudas ----------

    private Carrito carritoConTecladoYRaton() {
        Carrito carrito = new Carrito(ID_CLIENTE);
        carrito.anadir(ID_TECLADO, 2);
        carrito.anadir(ID_RATON, 3);
        when(repositorioCarrito.buscarPorCliente(new ObjectId(ID_CLIENTE))).thenReturn(Optional.of(carrito));
        return carrito;
    }

    private void productos(String vendedorTeclado, String vendedorRaton, boolean ratonVisible) {
        Producto teclado = Producto.builder().id(ID_TECLADO).idVendedor(vendedorTeclado).nombre("Teclado")
                .precio(new PrecioProducto(20.0, 10.0, 25.0)).stock(10).visible(true).build();
        Producto raton = Producto.builder().id(ID_RATON).idVendedor(vendedorRaton).nombre("Ratón")
                .precio(new PrecioProducto(5.5, null, null)).stock(10).visible(ratonVisible).build();
        when(repositorioProducto.findAllById(any())).thenReturn(List.of(teclado, raton));
    }

    private Pedido pedidoGuardado() {
        ArgumentCaptor<Pedido> captor = ArgumentCaptor.forClass(Pedido.class);
        verify(repositorioPedido).save(captor.capture());
        return captor.getValue();
    }
}