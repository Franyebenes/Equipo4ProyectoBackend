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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import com.esibuy.esibuy_backend.excepcion.CantidadNoValidaException;
import com.esibuy.esibuy_backend.excepcion.ProductoNoEnCarritoException;
import com.esibuy.esibuy_backend.excepcion.StockInsuficienteException;
import com.esibuy.esibuy_backend.modelo.Carrito;
import com.esibuy.esibuy_backend.modelo.PrecioProducto;
import com.esibuy.esibuy_backend.modelo.Producto;
import com.esibuy.esibuy_backend.repositorio.RepositorioCarrito;
import com.esibuy.esibuy_backend.repositorio.RepositorioProducto;
import com.mongodb.client.result.UpdateResult;

/** HU-22.3 Modificar cantidad y HU-22.4 Eliminar del carrito: ajuste del stock reservado. */
@ExtendWith(MockitoExtension.class)
class ServicioCarritoModificarTest {

    private static final String ID_CLIENTE = "64b000000000000000000001";
    private static final String ID_PRODUCTO = "64b000000000000000000002";
    private static final String ID_OTRO_PRODUCTO = "64b000000000000000000009";

    @Mock
    private RepositorioCarrito repositorioCarrito;
    @Mock
    private RepositorioProducto repositorioProducto;
    @Mock
    private MongoTemplate mongoTemplate;
    @InjectMocks
    private ServicioCarrito servicio;

    // ---------- HU-22.3 Modificar cantidad ----------

    @Test
    void subirLaCantidadReservaSoloLaDiferencia() {
        // Given: tiene 1 y pasa a 3
        Carrito carrito = carritoConProducto(1);
        productoVisible();
        hayStock(true);
        // When
        servicio.cambiarCantidad(ID_CLIENTE, ID_PRODUCTO, 3);
        // Then
        assertThat(incrementoDeStock()).isEqualTo(-2);
        assertThat(carrito.buscarLinea(ID_PRODUCTO).orElseThrow().getCantidad()).isEqualTo(3);
        verify(repositorioCarrito).save(carrito);
    }

    @Test
    void bajarLaCantidadDevuelveLaDiferenciaAlStock() {
        // Given: tiene 3 y pasa a 1
        Carrito carrito = carritoConProducto(3);
        // When
        servicio.cambiarCantidad(ID_CLIENTE, ID_PRODUCTO, 1);
        // Then
        assertThat(incrementoDeStock()).isEqualTo(2);
        assertThat(carrito.buscarLinea(ID_PRODUCTO).orElseThrow().getCantidad()).isEqualTo(1);
        verify(repositorioCarrito).save(carrito);
    }

    @Test
    void mismaCantidadNoTocaElStock() {
        // Given
        carritoConProducto(2);
        // When
        servicio.cambiarCantidad(ID_CLIENTE, ID_PRODUCTO, 2);
        // Then
        verifyNoInteractions(mongoTemplate);
    }

    @Test
    void subirSinStockSuficienteLanzaExcepcionYNoCambiaElCarrito() {
        // Given
        Carrito carrito = carritoConProducto(1);
        productoVisible();
        hayStock(false);
        // When / Then
        assertThatThrownBy(() -> servicio.cambiarCantidad(ID_CLIENTE, ID_PRODUCTO, 50))
                .isInstanceOf(StockInsuficienteException.class);
        assertThat(carrito.buscarLinea(ID_PRODUCTO).orElseThrow().getCantidad()).isEqualTo(1);
        verify(repositorioCarrito, never()).save(any());
    }

    @Test
    void cantidadCeroLanzaCantidadNoValida() {
        // When / Then: para quitar el producto se usa eliminar
        assertThatThrownBy(() -> servicio.cambiarCantidad(ID_CLIENTE, ID_PRODUCTO, 0))
                .isInstanceOf(CantidadNoValidaException.class);
        verifyNoInteractions(repositorioCarrito, mongoTemplate);
    }

    @Test
    void cambiarUnProductoQueNoEstaEnElCarritoLanzaNoEnCarrito() {
        // Given
        carritoConProducto(1);
        // When / Then
        assertThatThrownBy(() -> servicio.cambiarCantidad(ID_CLIENTE, ID_OTRO_PRODUCTO, 2))
                .isInstanceOf(ProductoNoEnCarritoException.class);
        verifyNoInteractions(mongoTemplate);
    }

    @Test
    void cambiarSinTenerCarritoLanzaNoEnCarrito() {
        // Given
        when(repositorioCarrito.buscarPorCliente(new ObjectId(ID_CLIENTE))).thenReturn(Optional.empty());
        // When / Then
        assertThatThrownBy(() -> servicio.cambiarCantidad(ID_CLIENTE, ID_PRODUCTO, 2))
                .isInstanceOf(ProductoNoEnCarritoException.class);
    }

    // ---------- HU-22.4 Eliminar ----------

    @Test
    void eliminarQuitaLaLineaYDevuelveTodasLasUnidadesAlStock() {
        // Given
        Carrito carrito = carritoConProducto(4);
        // When
        servicio.eliminar(ID_CLIENTE, ID_PRODUCTO);
        // Then
        assertThat(incrementoDeStock()).isEqualTo(4);
        assertThat(carrito.getLineas()).isEmpty();
        verify(repositorioCarrito).save(carrito);
    }

    @Test
    void eliminarUnProductoQueNoEstaEnElCarritoLanzaNoEnCarritoYNoTocaElStock() {
        // Given
        carritoConProducto(1);
        // When / Then
        assertThatThrownBy(() -> servicio.eliminar(ID_CLIENTE, ID_OTRO_PRODUCTO))
                .isInstanceOf(ProductoNoEnCarritoException.class);
        verifyNoInteractions(mongoTemplate);
        verify(repositorioCarrito, never()).save(any());
    }

    // ---------- Ayudas ----------

    private Carrito carritoConProducto(int cantidad) {
        Carrito carrito = new Carrito(ID_CLIENTE);
        carrito.anadir(ID_PRODUCTO, cantidad);
        when(repositorioCarrito.buscarPorCliente(new ObjectId(ID_CLIENTE))).thenReturn(Optional.of(carrito));
        return carrito;
    }

    private void productoVisible() {
        Producto producto = Producto.builder().id(ID_PRODUCTO).nombre("Teclado").stock(10).visible(true)
                .precio(new PrecioProducto(20.0, 0.0, 0.0)).build();
        when(repositorioProducto.findById(ID_PRODUCTO)).thenReturn(Optional.of(producto));
    }

    private void hayStock(boolean hay) {
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(Producto.class)))
                .thenReturn(UpdateResult.acknowledged(hay ? 1 : 0, hay ? 1L : 0L, null));
    }

    /** Cuanto se ha sumado (positivo) o restado (negativo) al stock del producto. */
    private Object incrementoDeStock() {
        ArgumentCaptor<Update> cambio = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(any(Query.class), cambio.capture(), eq(Producto.class));
        return ((Document) cambio.getValue().getUpdateObject().get("$inc")).get("stock");
    }
}