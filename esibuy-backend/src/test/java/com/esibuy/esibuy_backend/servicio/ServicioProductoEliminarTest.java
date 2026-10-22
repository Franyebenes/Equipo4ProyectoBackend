package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.esibuy.esibuy_backend.excepcion.ProductoNoEncontradoException;
import com.esibuy.esibuy_backend.modelo.PrecioProducto;
import com.esibuy.esibuy_backend.modelo.Producto;
import com.esibuy.esibuy_backend.repositorio.RepositorioCategoria;
import com.esibuy.esibuy_backend.repositorio.RepositorioProducto;

/**
 * HU Eliminar producto: el vendedor borra definitivamente un producto de su catalogo; solo uno suyo. Si no existe o
 * es de otro vendedor, no se borra nada.
 */
@ExtendWith(MockitoExtension.class)
class ServicioProductoEliminarTest {

    private static final String ID_VENDEDOR = "64b7f0c2a1b2c3d4e5f60001";
    private static final String ID_OTRO_VENDEDOR = "64b7f0c2a1b2c3d4e5f60002";
    private static final String ID_PRODUCTO = "64b7f0c2a1b2c3d4e5f60101";

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
                .idCategorias(List.of("64b7f0c2a1b2c3d4e5f60202"))
                .nombre("Lámpara")
                .precio(new PrecioProducto(80.0, null, null))
                .stock(4)
                .visible(true)
                .build();
    }

    @Test
    void eliminarProducto_productoPropio_loBorraDefinitivamente() {
        // Given
        when(repositorioProducto.findById(ID_PRODUCTO)).thenReturn(Optional.of(lampara(ID_VENDEDOR)));

        // When
        servicio.eliminarProducto(ID_VENDEDOR, ID_PRODUCTO);

        // Then
        verify(repositorioProducto).deleteById(ID_PRODUCTO);
    }

    @Test
    void eliminarProducto_productoDeOtroVendedor_lanzaNoEncontradoYNoBorraNada() {
        // Given: el producto existe pero es de otro vendedor
        when(repositorioProducto.findById(ID_PRODUCTO)).thenReturn(Optional.of(lampara(ID_OTRO_VENDEDOR)));

        // When / Then: igual que si no existiera, para no revelar que existe
        assertThatThrownBy(() -> servicio.eliminarProducto(ID_VENDEDOR, ID_PRODUCTO))
                .isInstanceOf(ProductoNoEncontradoException.class)
                .hasMessage("El producto no existe en tu catálogo");
        verify(repositorioProducto, never()).deleteById(anyString());
    }

    @Test
    void eliminarProducto_productoInexistente_lanzaNoEncontradoYNoBorraNada() {
        // Given: no existe (o ya se habia eliminado)
        when(repositorioProducto.findById(ID_PRODUCTO)).thenReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> servicio.eliminarProducto(ID_VENDEDOR, ID_PRODUCTO))
                .isInstanceOf(ProductoNoEncontradoException.class);
        verify(repositorioProducto, never()).deleteById(anyString());
    }
}
