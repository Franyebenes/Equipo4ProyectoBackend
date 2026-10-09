package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.esibuy.esibuy_backend.dto.ProductoAltaDTO;
import com.esibuy.esibuy_backend.dto.ProductoCatalogoDTO;
import com.esibuy.esibuy_backend.excepcion.ProductoInvalidoException;
import com.esibuy.esibuy_backend.modelo.Categoria;
import com.esibuy.esibuy_backend.modelo.PrecioProducto;
import com.esibuy.esibuy_backend.modelo.Producto;
import com.esibuy.esibuy_backend.repositorio.RepositorioCategoria;
import com.esibuy.esibuy_backend.repositorio.RepositorioProducto;

/**
 * HU-15.1 Alta de producto: se guarda con el vendedor de la sesion, visible y sin descuentos; y no se guarda nada si
 * falta un campo obligatorio, si el precio o el stock no son validos o si alguna categoria no existe.
 */
@ExtendWith(MockitoExtension.class)
class ServicioProductoAltaTest {

    private static final String ID_VENDEDOR = "64b7f0c2a1b2c3d4e5f60001";
    private static final String ID_PRODUCTO = "64b7f0c2a1b2c3d4e5f60101";
    private static final String ID_HOGAR = "64b7f0c2a1b2c3d4e5f60202";
    private static final String ID_INEXISTENTE = "64b7f0c2a1b2c3d4e5f60299";
    private static final String IMAGEN = "data:image/jpeg;base64,/9j/4AAQSkZJRg==";
    private static final String MENSAJE_OBLIGATORIOS = "Rellena todos los campos obligatorios";
    private static final String MENSAJE_VALORES =
            "El precio debe ser mayor que 0, el stock no puede ser negativo y las categorías deben existir";

    @Mock
    private RepositorioProducto repositorioProducto;
    @Mock
    private RepositorioCategoria repositorioCategoria;
    @InjectMocks
    private ServicioProductoImpl servicio;

    private static ProductoAltaDTO alta(String nombre, String descripcion, Double precio, List<String> categorias,
                                        Integer stock) {
        return new ProductoAltaDTO(nombre, descripcion, precio, categorias, stock, null);
    }

    @Test
    void crearProducto_datosValidos_guardaConElVendedorDeLaSesionVisibleYSinDescuentos() {
        // Given: la categoria existe y MongoDB asigna un id al guardar
        when(repositorioCategoria.findAllById(any()))
                .thenReturn(List.of(new Categoria(ID_HOGAR, "Hogar", "Muebles y decoración.")));
        when(repositorioProducto.save(any(Producto.class))).thenReturn(Producto.builder()
                .id(ID_PRODUCTO).idVendedor(ID_VENDEDOR).idCategorias(List.of(ID_HOGAR))
                .nombre("Lámpara").descripcion("Lámpara de sobremesa.").imagen(IMAGEN)
                .precio(new PrecioProducto(80.0, null, null)).stock(4).visible(true).build());

        // When: nombre y descripcion llegan con espacios sobrantes
        ProductoCatalogoDTO resultado = servicio.crearProducto(ID_VENDEDOR, new ProductoAltaDTO(
                "  Lámpara  ", "  Lámpara de sobremesa.  ", 80.0, List.of(ID_HOGAR), 4, IMAGEN));

        // Then: se guarda limpio, sin id (lo genera MongoDB), del vendedor de la sesion, visible y sin descuentos
        ArgumentCaptor<Producto> guardado = ArgumentCaptor.forClass(Producto.class);
        verify(repositorioProducto).save(guardado.capture());
        Producto producto = guardado.getValue();
        assertThat(producto.getId()).isNull();
        assertThat(producto.getIdVendedor()).isEqualTo(ID_VENDEDOR);
        assertThat(producto.getNombre()).isEqualTo("Lámpara");
        assertThat(producto.getDescripcion()).isEqualTo("Lámpara de sobremesa.");
        assertThat(producto.getIdCategorias()).containsExactly(ID_HOGAR);
        assertThat(producto.getImagen()).isEqualTo(IMAGEN);
        assertThat(producto.getPrecio().getBase()).isEqualTo(80.0);
        assertThat(producto.getPrecio().getDescuento()).isNull();
        assertThat(producto.getPrecio().getDescuentoPremium()).isNull();
        assertThat(producto.getStock()).isEqualTo(4);
        assertThat(producto.getVisible()).isTrue();
        // y se devuelve como en el listado, con la categoria por nombre
        assertThat(resultado).isEqualTo(new ProductoCatalogoDTO(ID_PRODUCTO, "Lámpara", "Lámpara de sobremesa.",
                IMAGEN, List.of("Hogar"), 80.0, null, null, 4, true));
    }

    @Test
    void crearProducto_sinImagenYStockCero_seGuardaIgualmente() {
        // Given: la imagen es opcional y 0 es un stock valido (producto agotado)
        when(repositorioCategoria.findAllById(any()))
                .thenReturn(List.of(new Categoria(ID_HOGAR, "Hogar", "Muebles y decoración.")));
        when(repositorioProducto.save(any(Producto.class))).thenAnswer(invocacion -> invocacion.getArgument(0));

        // When
        servicio.crearProducto(ID_VENDEDOR, alta("Lámpara", "Lámpara de sobremesa.", 80.0, List.of(ID_HOGAR), 0));

        // Then
        ArgumentCaptor<Producto> guardado = ArgumentCaptor.forClass(Producto.class);
        verify(repositorioProducto).save(guardado.capture());
        assertThat(guardado.getValue().getImagen()).isNull();
        assertThat(guardado.getValue().getStock()).isZero();
    }

    static Stream<ProductoAltaDTO> faltaUnCampoObligatorio() {
        return Stream.of(
                alta(null, "Descripción", 10.0, List.of(ID_HOGAR), 1),
                alta("   ", "Descripción", 10.0, List.of(ID_HOGAR), 1),
                alta("Lámpara", null, 10.0, List.of(ID_HOGAR), 1),
                alta("Lámpara", "   ", 10.0, List.of(ID_HOGAR), 1),
                alta("Lámpara", "Descripción", null, List.of(ID_HOGAR), 1),
                alta("Lámpara", "Descripción", 10.0, null, 1),
                alta("Lámpara", "Descripción", 10.0, List.of(), 1),
                alta("Lámpara", "Descripción", 10.0, List.of(ID_HOGAR), null));
    }

    @ParameterizedTest
    @MethodSource("faltaUnCampoObligatorio")
    void crearProducto_faltaUnCampoObligatorio_lanzaExcepcionConElMensajeYNoGuarda(ProductoAltaDTO datos) {
        // When / Then
        assertThatThrownBy(() -> servicio.crearProducto(ID_VENDEDOR, datos))
                .isInstanceOf(ProductoInvalidoException.class)
                .hasMessage(MENSAJE_OBLIGATORIOS);
        verify(repositorioProducto, never()).save(any());
    }

    static Stream<ProductoAltaDTO> precioOStockIncorrecto() {
        return Stream.of(
                alta("Lámpara", "Descripción", 0.0, List.of(ID_HOGAR), 1),
                alta("Lámpara", "Descripción", -5.0, List.of(ID_HOGAR), 1),
                alta("Lámpara", "Descripción", 10.0, List.of(ID_HOGAR), -1));
    }

    @ParameterizedTest
    @MethodSource("precioOStockIncorrecto")
    void crearProducto_precioOStockIncorrecto_lanzaExcepcionConElMensajeYNoGuarda(ProductoAltaDTO datos) {
        // When / Then
        assertThatThrownBy(() -> servicio.crearProducto(ID_VENDEDOR, datos))
                .isInstanceOf(ProductoInvalidoException.class)
                .hasMessage(MENSAJE_VALORES);
        verify(repositorioProducto, never()).save(any());
    }

    @Test
    void crearProducto_algunaCategoriaNoExiste_lanzaExcepcionYNoGuarda() {
        // Given: de las dos categorias elegidas solo existe una (la otra se borro o el id es inventado)
        when(repositorioCategoria.findAllById(any()))
                .thenReturn(List.of(new Categoria(ID_HOGAR, "Hogar", "Muebles y decoración.")));
        ProductoAltaDTO datos = alta("Lámpara", "Descripción", 10.0, List.of(ID_HOGAR, ID_INEXISTENTE), 1);

        // When / Then
        assertThatThrownBy(() -> servicio.crearProducto(ID_VENDEDOR, datos))
                .isInstanceOf(ProductoInvalidoException.class)
                .hasMessage(MENSAJE_VALORES);
        verify(repositorioProducto, never()).save(any());
    }
}
