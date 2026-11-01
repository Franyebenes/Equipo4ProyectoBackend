package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import com.esibuy.esibuy_backend.excepcion.CantidadNoValidaException;
import com.esibuy.esibuy_backend.excepcion.ProductoNoDisponibleException;
import com.esibuy.esibuy_backend.excepcion.ProductoSoloPremiumException;
import com.esibuy.esibuy_backend.excepcion.StockInsuficienteException;
import com.esibuy.esibuy_backend.modelo.Carrito;
import com.esibuy.esibuy_backend.modelo.PrecioProducto;
import com.esibuy.esibuy_backend.modelo.Producto;
import com.esibuy.esibuy_backend.repositorio.RepositorioCarrito;
import com.esibuy.esibuy_backend.repositorio.RepositorioProducto;
import com.mongodb.client.result.UpdateResult;

/** HU-22.2 Anadir al carrito (hecha con TDD). */
@ExtendWith(MockitoExtension.class)
class ServicioCarritoAnadirTest {

    private static final String ID_CLIENTE = "64b000000000000000000001";
    private static final String ID_PRODUCTO = "64b000000000000000000002";

    @Mock
    private RepositorioCarrito repositorioCarrito;
    @Mock
    private RepositorioProducto repositorioProducto;
    @Mock
    private MongoTemplate mongoTemplate;
    @InjectMocks
    private ServicioCarrito servicio;

    @Test
    void anadirProductoNuevoCreaUnaLineaConLaCantidad() {
        // Given
        productoEnBaseDeDatos(true);
        hayStock(true);
        carritoVacio();
        // When
        servicio.anadir(ID_CLIENTE, ID_PRODUCTO, 2, false);
        // Then
        Carrito carrito = carritoGuardado();
        assertThat(carrito.getIdCliente()).isEqualTo(ID_CLIENTE);
        assertThat(carrito.getLineas()).hasSize(1);
        assertThat(carrito.getLineas().get(0).getIdProducto()).isEqualTo(ID_PRODUCTO);
        assertThat(carrito.getLineas().get(0).getCantidad()).isEqualTo(2);
    }

    @Test
    void anadirProductoQueYaEstaSumaLaCantidad() {
        // Given
        productoEnBaseDeDatos(true);
        hayStock(true);
        Carrito existente = new Carrito(ID_CLIENTE);
        existente.anadir(ID_PRODUCTO, 1);
        when(repositorioCarrito.buscarPorCliente(new ObjectId(ID_CLIENTE))).thenReturn(Optional.of(existente));
        // When
        servicio.anadir(ID_CLIENTE, ID_PRODUCTO, 2, false);
        // Then
        Carrito carrito = carritoGuardado();
        assertThat(carrito.getLineas()).hasSize(1);
        assertThat(carrito.getLineas().get(0).getCantidad()).isEqualTo(3);
    }

    @Test
    void anadirReservaElStockSoloSiHaySuficiente() {
        // Given
        productoEnBaseDeDatos(true);
        hayStock(true);
        carritoVacio();
        // When
        servicio.anadir(ID_CLIENTE, ID_PRODUCTO, 2, false);
        // Then: "resta 2 al stock, solo si el stock es >= 2"
        ArgumentCaptor<Query> consulta = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> cambio = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(consulta.capture(), cambio.capture(), eq(Producto.class));
        assertThat(consulta.getValue().getQueryObject().get("stock")).isEqualTo(new Document("$gte", 2));
        Document inc = (Document) cambio.getValue().getUpdateObject().get("$inc");
        assertThat(inc.get("stock")).isEqualTo(-2);
    }

    @Test
    void anadirSinStockSuficienteLanzaExcepcionYNoGuardaElCarrito() {
        // Given
        productoEnBaseDeDatos(true);
        hayStock(false);
        // When / Then
        assertThatThrownBy(() -> servicio.anadir(ID_CLIENTE, ID_PRODUCTO, 50, false))
                .isInstanceOf(StockInsuficienteException.class);
        verify(repositorioCarrito, never()).save(any());
    }

    @Test
    void anadirProductoNoVisibleLanzaNoDisponible() {
        // Given
        productoEnBaseDeDatos(false);
        // When / Then
        assertThatThrownBy(() -> servicio.anadir(ID_CLIENTE, ID_PRODUCTO, 1, false))
                .isInstanceOf(ProductoNoDisponibleException.class);
        verifyNoInteractions(mongoTemplate, repositorioCarrito);
    }

    @Test
    void anadirProductoInexistenteLanzaNoDisponible() {
        // Given
        when(repositorioProducto.findById(ID_PRODUCTO)).thenReturn(Optional.empty());
        // When / Then
        assertThatThrownBy(() -> servicio.anadir(ID_CLIENTE, ID_PRODUCTO, 1, false))
                .isInstanceOf(ProductoNoDisponibleException.class);
        verifyNoInteractions(mongoTemplate, repositorioCarrito);
    }

    @Test
    void anadirConIdDeProductoMalFormadoLanzaNoDisponible() {
        // When / Then
        assertThatThrownBy(() -> servicio.anadir(ID_CLIENTE, "no-es-un-id", 1, false))
                .isInstanceOf(ProductoNoDisponibleException.class);
        verifyNoInteractions(repositorioProducto, mongoTemplate, repositorioCarrito);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void anadirConCantidadMenorQueUnoLanzaCantidadNoValida(int cantidad) {
        // When / Then
        assertThatThrownBy(() -> servicio.anadir(ID_CLIENTE, ID_PRODUCTO, cantidad, false))
                .isInstanceOf(CantidadNoValidaException.class);
        verifyNoInteractions(repositorioProducto, mongoTemplate, repositorioCarrito);
    }

    @Test
    void anadirGuardaCuandoSeModificoElCarritoParaQuePuedaCaducar() {
        // Given
        productoEnBaseDeDatos(true);
        hayStock(true);
        carritoVacio();
        // When
        servicio.anadir(ID_CLIENTE, ID_PRODUCTO, 1, false);
        // Then
        assertThat(carritoGuardado().getUltimaModificacion()).isNotNull();
    }

    @Test
    void anadirProductoSoloPremiumSiendoClienteNormalLanzaSoloPremiumYNoReservaStock() {
        // Given
        productoSoloPremiumEnBaseDeDatos();
        // When / Then
        assertThatThrownBy(() -> servicio.anadir(ID_CLIENTE, ID_PRODUCTO, 1, false))
                .isInstanceOf(ProductoSoloPremiumException.class);
        verifyNoInteractions(mongoTemplate, repositorioCarrito);
    }

    @Test
    void anadirProductoSoloPremiumSiendoPremiumLoAnade() {
        // Given
        productoSoloPremiumEnBaseDeDatos();
        hayStock(true);
        carritoVacio();
        // When
        servicio.anadir(ID_CLIENTE, ID_PRODUCTO, 1, true);
        // Then
        assertThat(carritoGuardado().buscarLinea(ID_PRODUCTO).orElseThrow().getCantidad()).isEqualTo(1);
    }


    private void productoSoloPremiumEnBaseDeDatos() {
        Producto producto = Producto.builder()
                .id(ID_PRODUCTO).nombre("Reloj").stock(10).visible(true).soloPremium(true)
                .precio(new PrecioProducto(100.0, null, 10.0))
                .build();
        when(repositorioProducto.findById(ID_PRODUCTO)).thenReturn(Optional.of(producto));
    }

    private void productoEnBaseDeDatos(boolean visible) {
        Producto producto = Producto.builder()
                .id(ID_PRODUCTO).nombre("Teclado").stock(10).visible(visible)
                .precio(new PrecioProducto(20.0, 0.0, 10.0))
                .build();
        when(repositorioProducto.findById(ID_PRODUCTO)).thenReturn(Optional.of(producto));
    }

    private void hayStock(boolean hay) {
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(Producto.class)))
                .thenReturn(UpdateResult.acknowledged(hay ? 1 : 0, hay ? 1L : 0L, null));
    }

    private void carritoVacio() {
        when(repositorioCarrito.buscarPorCliente(new ObjectId(ID_CLIENTE))).thenReturn(Optional.empty());
    }

    private Carrito carritoGuardado() {
        ArgumentCaptor<Carrito> captor = ArgumentCaptor.forClass(Carrito.class);
        verify(repositorioCarrito).save(captor.capture());
        return captor.getValue();
    }
}