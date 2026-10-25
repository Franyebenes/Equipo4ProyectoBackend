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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import com.esibuy.esibuy_backend.modelo.EstadoPedido;
import com.esibuy.esibuy_backend.modelo.Pedido;
import com.esibuy.esibuy_backend.modelo.PrecioProducto;
import com.esibuy.esibuy_backend.modelo.Producto;
import com.esibuy.esibuy_backend.repositorio.RepositorioCarrito;
import com.esibuy.esibuy_backend.repositorio.RepositorioPedido;
import com.esibuy.esibuy_backend.repositorio.RepositorioProducto;
import com.esibuy.esibuy_backend.repositorio.RepositorioValoracion;

/** HU-26 Realizar pedido: el carrito se convierte en un pedido pagado con los precios actuales y se vacia. */
@ExtendWith(MockitoExtension.class)
class ServicioPedidoRealizarTest {

    private static final String ID_CLIENTE = "64b000000000000000000001";
    private static final String ID_TECLADO = "64b000000000000000000002";
    private static final String ID_RATON = "64b000000000000000000003";
    private static final Instant AHORA = Instant.parse("2026-10-24T10:15:30Z");
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
    void realizarCreaUnPedidoPagadoConLosPreciosDelCarritoYLoVacia() {
        // Given: teclado 20 EUR con 10% x2 y raton 5.5 EUR sin descuento x3
        carritoCon(ID_TECLADO, 2, ID_RATON, 3);
        productosEnBaseDeDatos(true);
        when(repositorioPedido.save(any())).thenAnswer(invocacion -> invocacion.getArgument(0));
        // When
        PedidoDTO pedido = servicio.realizar(ID_CLIENTE, false, DIRECCION);
        // Then: 18 x 2 + 5.5 x 3 = 52.5
        assertThat(pedido.estado()).isEqualTo(EstadoPedido.PAGADO);
        assertThat(pedido.fecha()).isEqualTo(AHORA);
        assertThat(pedido.precioTotal()).isEqualTo(52.5);
        assertThat(pedido.direccionEnvio()).isEqualTo(DIRECCION);
        assertThat(pedido.lineas()).hasSize(2);
        assertThat(pedido.lineas().get(0).nombre()).isEqualTo("Teclado");
        assertThat(pedido.lineas().get(0).precioUnitario()).isEqualTo(18.0);
        assertThat(pedido.lineas().get(0).subtotal()).isEqualTo(36.0);
        assertThat(pedido.lineas().get(0).valoracion()).isNull();
        assertThat(pedidoGuardado().getIdCliente()).isEqualTo(ID_CLIENTE);
        assertThat(carritoGuardado().getLineas()).isEmpty();
        // El stock ya se reservo al anadir al carrito: aqui no se toca
        verifyNoInteractions(mongoTemplate);
    }

    @Test
    void realizarComoPremiumAplicaElDescuentoPremium() {
        // Given: teclado de 20 EUR con 10% normal y 25% premium
        carritoCon(ID_TECLADO, 1, ID_RATON, 1);
        productosEnBaseDeDatos(true);
        when(repositorioPedido.save(any())).thenAnswer(invocacion -> invocacion.getArgument(0));
        // When
        PedidoDTO pedido = servicio.realizar(ID_CLIENTE, true, DIRECCION);
        // Then: descuentos en cadena, 20 x 0.90 x 0.75 = 13.5; total 13.5 + 5.5 = 19
        assertThat(pedido.lineas().get(0).precioUnitario()).isEqualTo(13.5);
        assertThat(pedido.precioTotal()).isEqualTo(19.0);
    }

    @Test
    void realizarSinCarritoLanzaExcepcionYNoGuardaNada() {
        // Given
        when(repositorioCarrito.buscarPorCliente(new ObjectId(ID_CLIENTE))).thenReturn(Optional.empty());
        // When / Then
        assertThatThrownBy(() -> servicio.realizar(ID_CLIENTE, false, DIRECCION))
                .isInstanceOf(PedidoInvalidoException.class)
                .hasMessage(ServicioPedido.MENSAJE_CARRITO_VACIO);
        verify(repositorioPedido, never()).save(any());
    }

    @Test
    void realizarConUnProductoDesactivadoLanzaExcepcionYNoGuardaNada() {
        // Given
        carritoCon(ID_TECLADO, 2, ID_RATON, 3);
        productosEnBaseDeDatos(false);
        // When / Then
        assertThatThrownBy(() -> servicio.realizar(ID_CLIENTE, false, DIRECCION))
                .isInstanceOf(PedidoInvalidoException.class)
                .hasMessage(ServicioPedido.MENSAJE_PRODUCTOS_NO_DISPONIBLES);
        verify(repositorioPedido, never()).save(any());
        verify(repositorioCarrito, never()).save(any());
    }

    @Test
    void realizarIgnoraLosProductosBorradosYSiNoQuedaNingunoEsCarritoVacio() {
        // Given: el carrito tiene dos lineas pero ninguno de los productos existe ya
        carritoCon(ID_TECLADO, 2, ID_RATON, 3);
        when(repositorioProducto.findAllById(any())).thenReturn(List.of());
        // When / Then
        assertThatThrownBy(() -> servicio.realizar(ID_CLIENTE, false, DIRECCION))
                .isInstanceOf(PedidoInvalidoException.class)
                .hasMessage(ServicioPedido.MENSAJE_CARRITO_VACIO);
        verify(repositorioPedido, never()).save(any());
    }

    @Test
    void realizarSinDireccionLanzaExcepcionSinConsultarElCarrito() {
        assertThatThrownBy(() -> servicio.realizar(ID_CLIENTE, false, null))
                .isInstanceOf(PedidoInvalidoException.class)
                .hasMessage(ServicioPedido.MENSAJE_DIRECCION_INCOMPLETA);
        assertThatThrownBy(() -> servicio.realizar(ID_CLIENTE, false, new DireccionEnvioDTO("Calle Mayor 1", " ", "13001")))
                .isInstanceOf(PedidoInvalidoException.class)
                .hasMessage(ServicioPedido.MENSAJE_DIRECCION_INCOMPLETA);
        verifyNoInteractions(repositorioCarrito, repositorioPedido);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1300", "130011", "13A01", "abcde"})
    void realizarConCodigoPostalIncorrectoLanzaExcepcion(String codigoPostal) {
        assertThatThrownBy(() -> servicio.realizar(ID_CLIENTE, false, new DireccionEnvioDTO("Calle Mayor 1", "Ciudad Real", codigoPostal)))
                .isInstanceOf(PedidoInvalidoException.class)
                .hasMessage(ServicioPedido.MENSAJE_CODIGO_POSTAL_INVALIDO);
        verifyNoInteractions(repositorioCarrito, repositorioPedido);
    }

    @Test
    void realizarGuardaLaDireccionSinEspaciosSobrantes() {
        // Given
        carritoCon(ID_TECLADO, 1, ID_RATON, 1);
        productosEnBaseDeDatos(true);
        when(repositorioPedido.save(any())).thenAnswer(invocacion -> invocacion.getArgument(0));
        // When
        servicio.realizar(ID_CLIENTE, false, new DireccionEnvioDTO("  Calle Mayor 1 ", "Ciudad Real ", " 13001"));
        // Then
        assertThat(pedidoGuardado().getDireccionEnvio().getCalle()).isEqualTo("Calle Mayor 1");
        assertThat(pedidoGuardado().getDireccionEnvio().getCodigoPostal()).isEqualTo("13001");
    }

    // ---------- Ayudas ----------

    private void carritoCon(String idProducto1, int cantidad1, String idProducto2, int cantidad2) {
        Carrito carrito = new Carrito(ID_CLIENTE);
        carrito.anadir(idProducto1, cantidad1);
        carrito.anadir(idProducto2, cantidad2);
        when(repositorioCarrito.buscarPorCliente(new ObjectId(ID_CLIENTE))).thenReturn(Optional.of(carrito));
    }

    private void productosEnBaseDeDatos(boolean tecladoVisible) {
        Producto teclado = Producto.builder().id(ID_TECLADO).nombre("Teclado")
                .precio(new PrecioProducto(20.0, 10.0, 25.0)).stock(10).visible(tecladoVisible).build();
        Producto raton = Producto.builder().id(ID_RATON).nombre("Ratón")
                .precio(new PrecioProducto(5.5, null, null)).stock(10).visible(true).build();
        when(repositorioProducto.findAllById(any())).thenReturn(List.of(teclado, raton));
    }

    private Pedido pedidoGuardado() {
        ArgumentCaptor<Pedido> captor = ArgumentCaptor.forClass(Pedido.class);
        verify(repositorioPedido).save(captor.capture());
        return captor.getValue();
    }

    private Carrito carritoGuardado() {
        ArgumentCaptor<Carrito> captor = ArgumentCaptor.forClass(Carrito.class);
        verify(repositorioCarrito).save(captor.capture());
        return captor.getValue();
    }
}