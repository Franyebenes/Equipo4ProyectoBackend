package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.esibuy.esibuy_backend.dto.ProductoAltaDTO;
import com.esibuy.esibuy_backend.excepcion.ProductoInvalidoException;
import com.esibuy.esibuy_backend.modelo.Categoria;
import com.esibuy.esibuy_backend.modelo.PrecioProducto;
import com.esibuy.esibuy_backend.modelo.Producto;
import com.esibuy.esibuy_backend.repositorio.RepositorioCategoria;
import com.esibuy.esibuy_backend.repositorio.RepositorioProducto;

/**
 * Descuento premium en el alta y la modificacion de productos: opcional, entre 0 y 100 % y, como se suma al
 * descuento para todos, entre los dos no pueden pasar del 100 %.
 */
@ExtendWith(MockitoExtension.class)
class ServicioProductoDescuentoPremiumTest {

    private static final String ID_VENDEDOR = "64b7f0c2a1b2c3d4e5f60001";
    private static final String ID_PRODUCTO = "64b7f0c2a1b2c3d4e5f60101";
    private static final String ID_HOGAR = "64b7f0c2a1b2c3d4e5f60202";

    @Mock
    private RepositorioProducto repositorioProducto;
    @Mock
    private RepositorioCategoria repositorioCategoria;
    @InjectMocks
    private ServicioProductoImpl servicio;

    private static ProductoAltaDTO conDescuentos(Double descuento, Double descuentoPremium) {
        return new ProductoAltaDTO("Lampara", "Lampara de sobremesa.", 100.0, List.of(ID_HOGAR), 4, null, true,
                descuento, descuentoPremium, false);
    }

    private void seGuarda() {
        when(repositorioCategoria.findAllById(any()))
                .thenReturn(List.of(new Categoria(ID_HOGAR, "Hogar", "Muebles y decoración.")));
        when(repositorioProducto.save(any(Producto.class))).thenAnswer(invocacion -> invocacion.getArgument(0));
    }

    private PrecioProducto precioGuardado() {
        ArgumentCaptor<Producto> guardado = ArgumentCaptor.forClass(Producto.class);
        verify(repositorioProducto).save(guardado.capture());
        return guardado.getValue().getPrecio();
    }

    @Test
    void crearProducto_conLosDosDescuentos_guardaAmbosYElPremiumPagaLaSuma() {
        // Given
        seGuarda();

        // When: el ejemplo de la HU, un 10 % para todos y un 15 % extra para premium
        servicio.crearProducto(ID_VENDEDOR, conDescuentos(10.0, 15.0));

        // Then
        PrecioProducto precio = precioGuardado();
        assertThat(precio.getDescuento()).isEqualTo(10.0);
        assertThat(precio.getDescuentoPremium()).isEqualTo(15.0);
        assertThat(precio.precioFinal(true)).isEqualTo(75.0);
    }

    @ParameterizedTest
    @CsvSource(nullValues = "-", value = {"60, 40", "-, 100", "0, 100", "100, 0"})
    void crearProducto_descuentosQueSumanHasta100_seGuardan(Double descuento, Double descuentoPremium) {
        // Given
        seGuarda();

        // When
        servicio.crearProducto(ID_VENDEDOR, conDescuentos(descuento, descuentoPremium));

        // Then
        assertThat(precioGuardado().getDescuentoPremium()).isEqualTo(descuentoPremium);
    }

    @ParameterizedTest
    @CsvSource({"60, 50", "90, 11", "100, 1"})
    void crearProducto_descuentosQueSumanMasDe100_lanzaExcepcionYNoGuarda(double descuento, double descuentoPremium) {
        // When / Then
        assertThatThrownBy(() -> servicio.crearProducto(ID_VENDEDOR, conDescuentos(descuento, descuentoPremium)))
                .isInstanceOf(ProductoInvalidoException.class)
                .hasMessage(ServicioProductoImpl.MENSAJE_DESCUENTOS_SUPERAN_100);
        verify(repositorioProducto, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(doubles = {-1.0, 100.5})
    void crearProducto_descuentoPremiumFueraDeRango_lanzaExcepcionYNoGuarda(double descuentoPremium) {
        // When / Then
        assertThatThrownBy(() -> servicio.crearProducto(ID_VENDEDOR, conDescuentos(null, descuentoPremium)))
                .isInstanceOf(ProductoInvalidoException.class)
                .hasMessage(ServicioProductoImpl.MENSAJE_DESCUENTO_PREMIUM_INVALIDO);
        verify(repositorioProducto, never()).save(any());
    }

    @Test
    void modificarProducto_cambiaElDescuentoPremium() {
        // Given: el producto tenia un 10 % y un 5 % premium
        when(repositorioProducto.findById(ID_PRODUCTO)).thenReturn(Optional.of(Producto.builder()
                .id(ID_PRODUCTO).idVendedor(ID_VENDEDOR).idCategorias(List.of(ID_HOGAR)).nombre("Lampara")
                .descripcion("Lampara de sobremesa.").precio(new PrecioProducto(100.0, 10.0, 5.0)).stock(4)
                .visible(true).build()));
        seGuarda();

        // When
        servicio.modificarProducto(ID_VENDEDOR, ID_PRODUCTO, conDescuentos(10.0, 15.0), null);

        // Then
        PrecioProducto precio = precioGuardado();
        assertThat(precio.getDescuentoPremium()).isEqualTo(15.0);
        assertThat(precio.precioFinal(true)).isEqualTo(75.0);
    }

    @Test
    void modificarProducto_descuentosQueSumanMasDe100_lanzaExcepcionYNoGuarda() {
        // When / Then: se valida antes de buscar el producto
        assertThatThrownBy(() -> servicio.modificarProducto(ID_VENDEDOR, ID_PRODUCTO, conDescuentos(90.0, 20.0),
                null))
                .isInstanceOf(ProductoInvalidoException.class)
                .hasMessage(ServicioProductoImpl.MENSAJE_DESCUENTOS_SUPERAN_100);
        verify(repositorioProducto, never()).save(any());
    }
}
