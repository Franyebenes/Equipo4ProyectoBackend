package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;

import com.esibuy.esibuy_backend.dto.CarritoDTO;
import com.esibuy.esibuy_backend.dto.LineaCarritoDTO;
import com.esibuy.esibuy_backend.modelo.Carrito;
import com.esibuy.esibuy_backend.modelo.PrecioProducto;
import com.esibuy.esibuy_backend.modelo.Producto;
import com.esibuy.esibuy_backend.repositorio.RepositorioCarrito;
import com.esibuy.esibuy_backend.repositorio.RepositorioProducto;

/** HU-22.1 Ver carrito: precios, descuentos, subtotales y total. */
@ExtendWith(MockitoExtension.class)
class ServicioCarritoVerTest {

    private static final String ID_CLIENTE = "64b000000000000000000001";
    private static final String ID_TECLADO = "64b000000000000000000002";
    private static final String ID_RATON = "64b000000000000000000003";

    @Mock
    private RepositorioCarrito repositorioCarrito;
    @Mock
    private RepositorioProducto repositorioProducto;
    @Mock
    private MongoTemplate mongoTemplate;
    @InjectMocks
    private ServicioCarrito servicio;

    @Test
    void sinCarritoDevuelveCarritoVacioSinConsultarProductos() {
        // Given
        when(repositorioCarrito.buscarPorCliente(new ObjectId(ID_CLIENTE))).thenReturn(Optional.empty());
        // When
        CarritoDTO carrito = servicio.obtener(ID_CLIENTE, false);
        // Then
        assertThat(carrito.lineas()).isEmpty();
        assertThat(carrito.total()).isZero();
        assertThat(carrito.numeroArticulos()).isZero();
        verifyNoInteractions(repositorioProducto);
    }

    @Test
    void clienteNormalPagaConElDescuentoGeneralYSeSumaElTotal() {
        // Given: teclado 20 EUR con 10% (25% premium) x2 y raton 5.5 EUR sin descuento x3
        carritoCon(ID_TECLADO, 2, ID_RATON, 3);
        productosEnBaseDeDatos(teclado(true), raton());
        // When
        CarritoDTO carrito = servicio.obtener(ID_CLIENTE, false);
        // Then
        LineaCarritoDTO lineaTeclado = carrito.lineas().get(0);
        assertThat(lineaTeclado.precioOriginal()).isEqualTo(20.0);
        assertThat(lineaTeclado.precioUnitario()).isEqualTo(18.0);
        assertThat(lineaTeclado.subtotal()).isEqualTo(36.0);
        assertThat(carrito.lineas().get(1).subtotal()).isEqualTo(16.5);
        assertThat(carrito.total()).isEqualTo(52.5);
        assertThat(carrito.numeroArticulos()).isEqualTo(5);
    }

    @Test
    void premiumPagaConSuDescuento() {
        // Given
        carritoCon(ID_TECLADO, 2, ID_RATON, 3);
        productosEnBaseDeDatos(teclado(true), raton());
        // When
        CarritoDTO carrito = servicio.obtener(ID_CLIENTE, true);
        // Then: los dos descuentos se suman: 10% + 25% = 35%, 20 -> 13 -> 26 + 16.5
        assertThat(carrito.lineas().get(0).precioUnitario()).isEqualTo(13.0);
        assertThat(carrito.total()).isEqualTo(42.5);
    }

    @Test
    void premiumTieneLosDosDescuentosSumadosComoEnElCatalogo() {
        // Given: 20% para todos y 10% mas para premium
        PrecioProducto precio = new PrecioProducto(100.0, 20.0, 10.0);
        // When / Then: el premium tiene un 30% (100 -> 70); el cliente normal, un 20% (80)
        assertThat(ServicioCarrito.precioFinal(precio, true)).isEqualTo(70.0);
        assertThat(ServicioCarrito.precioFinal(precio, false)).isEqualTo(80.0);
    }

    @Test
    void sinDescuentosSePagaElPrecioBase() {
        assertThat(ServicioCarrito.precioFinal(new PrecioProducto(12.5, null, null), true)).isEqualTo(12.5);
    }

    @Test
    void productoDesactivadoSeMuestraComoNoDisponibleYNoCuentaEnElTotal() {
        // Given
        carritoCon(ID_TECLADO, 2, ID_RATON, 3);
        productosEnBaseDeDatos(teclado(false), raton());
        // When
        CarritoDTO carrito = servicio.obtener(ID_CLIENTE, false);
        // Then
        assertThat(carrito.lineas()).hasSize(2);
        assertThat(carrito.lineas().get(0).disponible()).isFalse();
        assertThat(carrito.total()).isEqualTo(16.5);
        assertThat(carrito.numeroArticulos()).isEqualTo(3);
    }

    @Test
    void productoBorradoDeLaBaseDeDatosNoSeMuestra() {
        // Given: el teclado ya no existe
        carritoCon(ID_TECLADO, 2, ID_RATON, 3);
        productosEnBaseDeDatos(raton());
        // When
        CarritoDTO carrito = servicio.obtener(ID_CLIENTE, false);
        // Then
        assertThat(carrito.lineas()).hasSize(1);
        assertThat(carrito.lineas().get(0).idProducto()).isEqualTo(ID_RATON);
    }

    // ---------- Ayudas ----------

    private void carritoCon(String idProducto1, int cantidad1, String idProducto2, int cantidad2) {
        Carrito carrito = new Carrito(ID_CLIENTE);
        carrito.anadir(idProducto1, cantidad1);
        carrito.anadir(idProducto2, cantidad2);
        when(repositorioCarrito.buscarPorCliente(new ObjectId(ID_CLIENTE))).thenReturn(Optional.of(carrito));
    }

    private void productosEnBaseDeDatos(Producto... productos) {
        when(repositorioProducto.findAllById(any())).thenReturn(List.of(productos));
    }

    private static Producto teclado(boolean visible) {
        return Producto.builder().id(ID_TECLADO).nombre("Teclado").stock(10).visible(visible)
                .precio(new PrecioProducto(20.0, 10.0, 25.0)).build();
    }

    private static Producto raton() {
        return Producto.builder().id(ID_RATON).nombre("Raton").stock(10).visible(true)
                .precio(new PrecioProducto(5.5, 0.0, 0.0)).build();
    }
}