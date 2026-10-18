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
 * HU Activar/Desactivar producto: el vendedor cambia la visibilidad de sus productos sin borrarlos; solo de los
 * suyos, y el cambio se guarda y se devuelve con el estado nuevo.
 */
@ExtendWith(MockitoExtension.class)
class ServicioProductoEstadoTest {

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

    private static Producto lampara(String idVendedor, boolean visible) {
        return Producto.builder()
                .id(ID_PRODUCTO)
                .idVendedor(idVendedor)
                .idCategorias(List.of(ID_HOGAR))
                .nombre("Lámpara")
                .descripcion("Lámpara de sobremesa.")
                .precio(new PrecioProducto(80.0, null, null))
                .stock(4)
                .visible(visible)
                .build();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void cambiarEstado_productoPropio_guardaElEstadoNuevoYLoDevuelve(boolean activo) {
        // Given: el producto esta en el estado contrario
        when(repositorioProducto.findById(ID_PRODUCTO)).thenReturn(Optional.of(lampara(ID_VENDEDOR, !activo)));
        when(repositorioProducto.save(any(Producto.class))).thenAnswer(invocacion -> invocacion.getArgument(0));
        when(repositorioCategoria.findAllById(any()))
                .thenReturn(List.of(new Categoria(ID_HOGAR, "Hogar", "Muebles y decoración.")));

        // When
        ProductoCatalogoDTO resultado = servicio.cambiarEstado(ID_VENDEDOR, ID_PRODUCTO, activo);

        // Then: se guarda con el estado nuevo y sin tocar el resto de datos
        ArgumentCaptor<Producto> guardado = ArgumentCaptor.forClass(Producto.class);
        verify(repositorioProducto).save(guardado.capture());
        assertThat(guardado.getValue().getVisible()).isEqualTo(activo);
        assertThat(guardado.getValue().getNombre()).isEqualTo("Lámpara");
        assertThat(guardado.getValue().getStock()).isEqualTo(4);
        assertThat(resultado.visible()).isEqualTo(activo);
        assertThat(resultado.categorias()).containsExactly("Hogar");
    }

    @Test
    void cambiarEstado_productoDeOtroVendedor_lanzaNoEncontradoYNoGuarda() {
        // Given: el producto existe pero es de otro vendedor
        when(repositorioProducto.findById(ID_PRODUCTO)).thenReturn(Optional.of(lampara(ID_OTRO_VENDEDOR, true)));

        // When / Then: igual que si no existiera, para no revelar que existe
        assertThatThrownBy(() -> servicio.cambiarEstado(ID_VENDEDOR, ID_PRODUCTO, false))
                .isInstanceOf(ProductoNoEncontradoException.class)
                .hasMessage("El producto no existe en tu catálogo");
        verify(repositorioProducto, never()).save(any());
    }

    @Test
    void cambiarEstado_productoInexistente_lanzaNoEncontradoYNoGuarda() {
        // Given
        when(repositorioProducto.findById(ID_PRODUCTO)).thenReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> servicio.cambiarEstado(ID_VENDEDOR, ID_PRODUCTO, false))
                .isInstanceOf(ProductoNoEncontradoException.class);
        verify(repositorioProducto, never()).save(any());
    }

    @Test
    void cambiarEstado_sinIndicarElEstado_lanzaExcepcionConElMensajeYNoConsultaNiGuarda() {
        // When / Then
        assertThatThrownBy(() -> servicio.cambiarEstado(ID_VENDEDOR, ID_PRODUCTO, null))
                .isInstanceOf(ProductoInvalidoException.class)
                .hasMessage(ServicioProductoImpl.MENSAJE_ESTADO_OBLIGATORIO);
        verify(repositorioProducto, never()).findById(anyString());
        verify(repositorioProducto, never()).save(any());
    }
}
