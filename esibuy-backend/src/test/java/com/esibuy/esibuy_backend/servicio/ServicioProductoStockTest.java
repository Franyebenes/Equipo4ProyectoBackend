package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.esibuy.esibuy_backend.dto.ProductoCatalogoDTO;
import com.esibuy.esibuy_backend.excepcion.ProductoInvalidoException;
import com.esibuy.esibuy_backend.excepcion.ProductoNoEncontradoException;
import com.esibuy.esibuy_backend.modelo.Categoria;
import com.esibuy.esibuy_backend.modelo.PrecioProducto;
import com.esibuy.esibuy_backend.modelo.Producto;
import com.esibuy.esibuy_backend.repositorio.RepositorioCategoria;
import com.esibuy.esibuy_backend.repositorio.RepositorioProducto;

/**
 * HU Gestion de stock: el vendedor aumenta o disminuye las unidades de sus productos; solo de los suyos y con un
 * entero no negativo. El cambio se guarda y se devuelve la cantidad actualizada.
 */
@ExtendWith(MockitoExtension.class)
class ServicioProductoStockTest {

    private static final String ID_VENDEDOR = "64b7f0c2a1b2c3d4e5f60001";
    private static final String ID_OTRO_VENDEDOR = "64b7f0c2a1b2c3d4e5f60002";
    private static final String ID_PRODUCTO = "64b7f0c2a1b2c3d4e5f60101";
    private static final String ID_HOGAR = "64b7f0c2a1b2c3d4e5f60202";

    @Mock
    private RepositorioProducto repositorioProducto;
    @Mock
    private RepositorioCategoria repositorioCategoria;
    @InjectMocks
    private ServicioProductoImpl servicio;

    private static Producto lampara(String idVendedor) {
        return Producto.builder()
                .id(ID_PRODUCTO)
                .idVendedor(idVendedor)
                .idCategorias(List.of(ID_HOGAR))
                .nombre("Lámpara")
                .descripcion("Lámpara de sobremesa.")
                .precio(new PrecioProducto(80.0, null, null))
                .stock(4)
                .visible(true)
                .build();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 3, 25})
    void cambiarStock_productoPropio_guardaLaCantidadNuevaYLaDevuelve(int stock) {
        // Given: tenia 4 unidades; se aumentan o se disminuyen (0 = sin stock)
        when(repositorioProducto.findById(ID_PRODUCTO)).thenReturn(Optional.of(lampara(ID_VENDEDOR)));
        when(repositorioProducto.save(any(Producto.class))).thenAnswer(invocacion -> invocacion.getArgument(0));
        when(repositorioCategoria.findAllById(any()))
                .thenReturn(List.of(new Categoria(ID_HOGAR, "Hogar", "Muebles y decoración.")));

        // When
        ProductoCatalogoDTO resultado = servicio.cambiarStock(ID_VENDEDOR, ID_PRODUCTO, stock, null);

        // Then: se guarda la cantidad nueva sin tocar el resto de datos
        ArgumentCaptor<Producto> guardado = ArgumentCaptor.forClass(Producto.class);
        verify(repositorioProducto).save(guardado.capture());
        assertThat(guardado.getValue().getStock()).isEqualTo(stock);
        assertThat(guardado.getValue().getVisible()).isTrue();
        assertThat(guardado.getValue().getPrecio().getBase()).isEqualTo(80.0);
        assertThat(resultado.stock()).isEqualTo(stock);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, -50})
    void cambiarStock_negativo_lanzaExcepcionConElMensajeYNoConsultaNiGuarda(int stock) {
        // When / Then
        assertThatThrownBy(() -> servicio.cambiarStock(ID_VENDEDOR, ID_PRODUCTO, stock, null))
                .isInstanceOf(ProductoInvalidoException.class)
                .hasMessage(ServicioProductoImpl.MENSAJE_STOCK_NEGATIVO);
        verify(repositorioProducto, never()).findById(anyString());
        verify(repositorioProducto, never()).save(any());
    }

    @Test
    void cambiarStock_sinCantidad_lanzaExcepcionConElMensajeYNoConsultaNiGuarda() {
        // When / Then
        assertThatThrownBy(() -> servicio.cambiarStock(ID_VENDEDOR, ID_PRODUCTO, null, null))
                .isInstanceOf(ProductoInvalidoException.class)
                .hasMessage(ServicioProductoImpl.MENSAJE_STOCK_OBLIGATORIO);
        verify(repositorioProducto, never()).findById(anyString());
        verify(repositorioProducto, never()).save(any());
    }

    @Test
    void cambiarStock_productoDeOtroVendedor_lanzaNoEncontradoYNoGuarda() {
        // Given: el producto existe pero es de otro vendedor
        when(repositorioProducto.findById(ID_PRODUCTO)).thenReturn(Optional.of(lampara(ID_OTRO_VENDEDOR)));

        // When / Then: igual que si no existiera, para no revelar que existe
        assertThatThrownBy(() -> servicio.cambiarStock(ID_VENDEDOR, ID_PRODUCTO, 10, null))
                .isInstanceOf(ProductoNoEncontradoException.class)
                .hasMessage("El producto no existe en tu catálogo");
        verify(repositorioProducto, never()).save(any());
    }

    @Test
    void cambiarStock_productoInexistente_lanzaNoEncontradoYNoGuarda() {
        // Given
        when(repositorioProducto.findById(ID_PRODUCTO)).thenReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> servicio.cambiarStock(ID_VENDEDOR, ID_PRODUCTO, 10, null))
                .isInstanceOf(ProductoNoEncontradoException.class);
        verify(repositorioProducto, never()).save(any());
    }
}
