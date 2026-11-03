package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.esibuy.esibuy_backend.dto.ProductoCatalogoDTO;
import com.esibuy.esibuy_backend.excepcion.ProductoNoEncontradoException;
import com.esibuy.esibuy_backend.modelo.Categoria;
import com.esibuy.esibuy_backend.modelo.PrecioProducto;
import com.esibuy.esibuy_backend.modelo.Producto;
import com.esibuy.esibuy_backend.repositorio.RepositorioCategoria;
import com.esibuy.esibuy_backend.repositorio.RepositorioProducto;

/**
 * HU-15.2 Ver catalogo propio: solo los productos del vendedor, con sus categorias por nombre y ordenados; detalle
 * de un producto propio, de otro vendedor e inexistente.
 */
@ExtendWith(MockitoExtension.class)
class ServicioProductoCatalogoTest {

    private static final String ID_VENDEDOR = "64b7f0c2a1b2c3d4e5f60001";
    private static final String ID_OTRO_VENDEDOR = "64b7f0c2a1b2c3d4e5f60002";
    private static final String ID_PRODUCTO = "64b7f0c2a1b2c3d4e5f60101";
    private static final String ID_MODA = "64b7f0c2a1b2c3d4e5f60201";
    private static final String ID_HOGAR = "64b7f0c2a1b2c3d4e5f60202";

    @Mock
    private RepositorioProducto repositorioProducto;
    @Mock
    private RepositorioCategoria repositorioCategoria;
    @InjectMocks
    private ServicioProductoImpl servicio;

    private static Producto producto(String id, String idVendedor, String nombre, List<String> categorias) {
        return Producto.builder()
                .id(id)
                .idVendedor(idVendedor)
                .idCategorias(categorias)
                .nombre(nombre)
                .descripcion("Descripción de " + nombre)
                .precio(new PrecioProducto(19.99, 10.0, null))
                .stock(5)
                .visible(true)
                .build();
    }

    @Test
    void listarProductosDelVendedor_consultaPorSuIdYDevuelveCategoriasPorNombreOrdenadosEnEspanol() {
        // Given: MongoDB los devuelve sin orden y uno empieza por letra con tilde
        when(repositorioProducto.buscarPorVendedor(new ObjectId(ID_VENDEDOR))).thenReturn(List.of(
                producto("3", ID_VENDEDOR, "Lámpara de pie", List.of(ID_HOGAR)),
                producto("1", ID_VENDEDOR, "Ábaco de madera", List.of(ID_HOGAR, ID_MODA)),
                producto("2", ID_VENDEDOR, "Bolso de piel", List.of(ID_MODA))));
        when(repositorioCategoria.findAllById(any())).thenReturn(List.of(
                new Categoria(ID_MODA, "Moda", "Ropa y complementos."),
                new Categoria(ID_HOGAR, "Hogar", "Muebles y decoración.")));

        // When
        List<ProductoCatalogoDTO> productos = servicio.listarProductosDelVendedor(ID_VENDEDOR);

        // Then: la tilde no manda el producto al final y cada uno lleva los nombres de sus categorias
        assertThat(productos).extracting(ProductoCatalogoDTO::nombre)
                .containsExactly("Ábaco de madera", "Bolso de piel", "Lámpara de pie");
        assertThat(productos.get(0).categorias()).containsExactly("Hogar", "Moda");
        assertThat(productos.get(0).precio()).isEqualTo(19.99);
        assertThat(productos.get(0).descuento()).isEqualTo(10.0);
        assertThat(productos.get(0).stock()).isEqualTo(5);
        assertThat(productos.get(0).visible()).isTrue();
    }

    @Test
    void listarProductosDelVendedor_sinProductos_devuelveListaVaciaYNoConsultaCategorias() {
        // Given: el vendedor todavia no ha dado de alta ningun producto
        when(repositorioProducto.buscarPorVendedor(new ObjectId(ID_VENDEDOR))).thenReturn(List.of());

        // When / Then
        assertThat(servicio.listarProductosDelVendedor(ID_VENDEDOR)).isEmpty();
        verify(repositorioCategoria, never()).findAllById(any());
    }

    @Test
    void obtenerProductoDelVendedor_propio_devuelveSuDetalleActual() {
        // Given
        when(repositorioProducto.findById(ID_PRODUCTO))
                .thenReturn(Optional.of(producto(ID_PRODUCTO, ID_VENDEDOR, "Bolso de piel", List.of(ID_MODA))));
        when(repositorioCategoria.findAllById(any()))
                .thenReturn(List.of(new Categoria(ID_MODA, "Moda", "Ropa y complementos.")));

        // When
        ProductoCatalogoDTO detalle = servicio.obtenerProductoDelVendedor(ID_VENDEDOR, ID_PRODUCTO);

        // Then
        assertThat(detalle).isEqualTo(new ProductoCatalogoDTO(ID_PRODUCTO, "Bolso de piel",
                "Descripción de Bolso de piel", null, List.of("Moda"), 19.99, 10.0, null, 5, true));
    }

    @Test
    void obtenerProductoDelVendedor_deOtroVendedor_lanzaNoEncontradoSinRevelarQueExiste() {
        // Given: el producto existe pero es de otro vendedor
        when(repositorioProducto.findById(ID_PRODUCTO))
                .thenReturn(Optional.of(producto(ID_PRODUCTO, ID_OTRO_VENDEDOR, "Bolso de piel", List.of(ID_MODA))));

        // When / Then
        assertThatThrownBy(() -> servicio.obtenerProductoDelVendedor(ID_VENDEDOR, ID_PRODUCTO))
                .isInstanceOf(ProductoNoEncontradoException.class)
                .hasMessage("El producto no existe en tu catálogo");
        verify(repositorioCategoria, never()).findAllById(any());
    }

    @Test
    void obtenerProductoDelVendedor_inexistente_lanzaNoEncontrado() {
        // Given
        when(repositorioProducto.findById(ID_PRODUCTO)).thenReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> servicio.obtenerProductoDelVendedor(ID_VENDEDOR, ID_PRODUCTO))
                .isInstanceOf(ProductoNoEncontradoException.class);
    }
}
