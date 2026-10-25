package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.bson.types.ObjectId;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.esibuy.esibuy_backend.dto.Disponibilidad;
import com.esibuy.esibuy_backend.dto.ProductoDetalleDTO;
import com.esibuy.esibuy_backend.excepcion.ProductoNoDisponibleException;
import com.esibuy.esibuy_backend.modelo.Categoria;
import com.esibuy.esibuy_backend.modelo.PrecioProducto;
import com.esibuy.esibuy_backend.modelo.Producto;
import com.esibuy.esibuy_backend.modelo.Valoracion;
import com.esibuy.esibuy_backend.repositorio.RepositorioCategoria;
import com.esibuy.esibuy_backend.repositorio.RepositorioProducto;
import com.esibuy.esibuy_backend.repositorio.RepositorioValoracion;

/**
 * HU-21 Ver detalle del producto: ficha completa de un producto activo con su disponibilidad (sin el stock exacto) y
 * el precio segun el tipo de cliente; un producto inactivo o inexistente no se puede consultar.
 */
@ExtendWith(MockitoExtension.class)
class ServicioCatalogoDetalleTest {

    private static final String ID_PRODUCTO = "64b7f0c2a1b2c3d4e5f60101";
    private static final String ID_MODA = "64b7f0c2a1b2c3d4e5f60201";
    private static final String ID_BORRADA = "64b7f0c2a1b2c3d4e5f60202";
    private static final String IMAGEN = "data:image/png;base64,iVBORw0KGgo=";

    @Mock
    private RepositorioProducto repositorioProducto;
    @Mock
    private RepositorioCategoria repositorioCategoria;
    @Mock
    private RepositorioValoracion repositorioValoracion;
    @InjectMocks
    private ServicioCatalogo servicio;

    // 100 € con un 20 % para todos y un 10 % mas para premium; quedan 3 unidades
    private static Producto reloj(boolean activo) {
        return Producto.builder()
                .id(ID_PRODUCTO)
                .nombre("Reloj")
                .descripcion("Reloj de pulsera de acero.")
                .imagen(IMAGEN)
                .idCategorias(List.of(ID_MODA, ID_BORRADA))
                .precio(new PrecioProducto(100.0, 20.0, 10.0))
                .stock(3)
                .visible(activo)
                .soloPremium(true)
                .build();
    }

    @Test
    void obtener_productoActivo_devuelveSuFichaCompletaSinElStockExacto() {
        // Given: una de sus categorias se ha borrado
        when(repositorioProducto.findById(ID_PRODUCTO)).thenReturn(Optional.of(reloj(true)));
        when(repositorioCategoria.findAllById(any()))
                .thenReturn(List.of(new Categoria(ID_MODA, "Moda", "Ropa y complementos.")));

        // When
        ProductoDetalleDTO ficha = servicio.obtener(ID_PRODUCTO, false);

        // Then
        assertThat(ficha.nombre()).isEqualTo("Reloj");
        assertThat(ficha.descripcion()).isEqualTo("Reloj de pulsera de acero.");
        assertThat(ficha.imagen()).isEqualTo(IMAGEN);
        assertThat(ficha.categorias()).containsExactly("Moda");
        assertThat(ficha.precio()).isEqualTo(100.0);
        assertThat(ficha.descuento()).isEqualTo(20.0);
        assertThat(ficha.descuentoPremium()).isEqualTo(10.0);
        assertThat(ficha.soloPremium()).isTrue();
        assertThat(ficha.disponibilidad()).isEqualTo(Disponibilidad.POCO_STOCK);
    }

    @Test
    void obtener_precioFinal_soloElClientePremiumTieneLosDosDescuentos() {
        // Given
        when(repositorioProducto.findById(ID_PRODUCTO)).thenReturn(Optional.of(reloj(true)));
        when(repositorioCategoria.findAllById(any())).thenReturn(List.of());

        // When / Then
        assertThat(servicio.obtener(ID_PRODUCTO, false).precioFinal()).isEqualTo(80.0);
        assertThat(servicio.obtener(ID_PRODUCTO, true).precioFinal()).isEqualTo(72.0);
    }

    @Test
    void obtener_sinValoracionesTodavia_devuelveMediaVaciaYCeroValoraciones() {
        // Given
        when(repositorioProducto.findById(ID_PRODUCTO)).thenReturn(Optional.of(reloj(true)));
        when(repositorioCategoria.findAllById(any())).thenReturn(List.of());

        // When
        ProductoDetalleDTO ficha = servicio.obtener(ID_PRODUCTO, false);

        // Then
        assertThat(ficha.valoracionMedia()).isNull();
        assertThat(ficha.numeroValoraciones()).isZero();
    }

    @Test
    void obtener_conValoraciones_devuelveSuMediaYCuantasHay() {
        // Given: dos clientes le han dado 5 y 4 estrellas
        when(repositorioProducto.findById(ID_PRODUCTO)).thenReturn(Optional.of(reloj(true)));
        when(repositorioCategoria.findAllById(any())).thenReturn(List.of());
        when(repositorioValoracion.buscarPorProducto(new ObjectId(ID_PRODUCTO))).thenReturn(List.of(
                new Valoracion(ID_PRODUCTO, "64b7f0c2a1b2c3d4e5f60001", "64b7f0c2a1b2c3d4e5f60301", 5),
                new Valoracion(ID_PRODUCTO, "64b7f0c2a1b2c3d4e5f60002", "64b7f0c2a1b2c3d4e5f60302", 4)));

        // When
        ProductoDetalleDTO ficha = servicio.obtener(ID_PRODUCTO, false);

        // Then
        assertThat(ficha.valoracionMedia()).isEqualTo(4.5);
        assertThat(ficha.numeroValoraciones()).isEqualTo(2);
    }

    @Test
    void obtener_productoInactivo_lanzaProductoNoDisponible() {
        // Given: el vendedor lo ha desactivado
        when(repositorioProducto.findById(ID_PRODUCTO)).thenReturn(Optional.of(reloj(false)));

        // When / Then
        assertThatThrownBy(() -> servicio.obtener(ID_PRODUCTO, false))
                .isInstanceOf(ProductoNoDisponibleException.class);
    }

    @Test
    void obtener_productoQueNoExiste_lanzaProductoNoDisponible() {
        // Given
        when(repositorioProducto.findById(ID_PRODUCTO)).thenReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> servicio.obtener(ID_PRODUCTO, false))
                .isInstanceOf(ProductoNoDisponibleException.class);
    }
}