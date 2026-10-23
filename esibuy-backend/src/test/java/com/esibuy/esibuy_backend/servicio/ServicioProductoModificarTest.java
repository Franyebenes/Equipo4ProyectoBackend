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
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.esibuy.esibuy_backend.dto.ProductoAltaDTO;
import com.esibuy.esibuy_backend.dto.ProductoCatalogoDTO;
import com.esibuy.esibuy_backend.excepcion.ProductoInvalidoException;
import com.esibuy.esibuy_backend.excepcion.ProductoNoEncontradoException;
import com.esibuy.esibuy_backend.modelo.Categoria;
import com.esibuy.esibuy_backend.modelo.PrecioProducto;
import com.esibuy.esibuy_backend.modelo.Producto;
import com.esibuy.esibuy_backend.repositorio.RepositorioCategoria;
import com.esibuy.esibuy_backend.repositorio.RepositorioProducto;
import com.esibuy.esibuy_backend.util.Constantes;

/**
 * HU Modificar producto: el vendedor edita los datos de sus productos (nombre, descripcion, precio, descuento,
 * categorias, imagen, stock y estado) con las mismas validaciones que el alta; solo de los suyos.
 */
@ExtendWith(MockitoExtension.class)
class ServicioProductoModificarTest {

    private static final String ID_VENDEDOR = "64b7f0c2a1b2c3d4e5f60001";
    private static final String ID_OTRO_VENDEDOR = "64b7f0c2a1b2c3d4e5f60002";
    private static final String ID_PRODUCTO = "64b7f0c2a1b2c3d4e5f60101";
    private static final String ID_HOGAR = "64b7f0c2a1b2c3d4e5f60202";
    private static final String ID_ILUMINACION = "64b7f0c2a1b2c3d4e5f60203";
    private static final String ID_INEXISTENTE = "64b7f0c2a1b2c3d4e5f60299";
    private static final String IMAGEN_ANTIGUA = "data:image/jpeg;base64,/9j/4AAQSkZJRg==";
    private static final String IMAGEN_NUEVA = "data:image/png;base64,iVBORw0KGgo=";
    private static final Categoria HOGAR = new Categoria(ID_HOGAR, "Hogar", "Muebles y decoración.");
    private static final Categoria ILUMINACION =
            new Categoria(ID_ILUMINACION, "Iluminación", "Lámparas y bombillas.");

    @Mock
    private RepositorioProducto repositorioProducto;
    @Mock
    private RepositorioCategoria repositorioCategoria;
    @InjectMocks
    private ServicioProductoImpl servicio;

    // Producto guardado: desactivado, con 4 unidades y un descuento premium que el vendedor no gestiona
    private static Producto lampara(String idVendedor) {
        return Producto.builder()
                .id(ID_PRODUCTO)
                .idVendedor(idVendedor)
                .idCategorias(List.of(ID_HOGAR))
                .nombre("Lampara")
                .descripcion("Lampara de sobremesa.")
                .imagen(IMAGEN_ANTIGUA)
                .precio(new PrecioProducto(80.0, 10.0, 5.0))
                .stock(4)
                .visible(false)
                .build();
    }

    private static ProductoAltaDTO cambios(String nombre, String descripcion, Double precio, List<String> categorias,
                                           Integer stock) {
        return new ProductoAltaDTO(nombre, descripcion, precio, categorias, stock, null, null, null, null, null);
    }

    @Test
    void modificarProducto_productoPropio_guardaTodosLosDatosNuevosEnElMismoProducto() {
        // Given
        when(repositorioCategoria.findAllById(any())).thenReturn(List.of(HOGAR, ILUMINACION));
        when(repositorioProducto.findById(ID_PRODUCTO)).thenReturn(Optional.of(lampara(ID_VENDEDOR)));
        when(repositorioProducto.save(any(Producto.class))).thenAnswer(invocacion -> invocacion.getArgument(0));

        // When: nombre y descripcion llegan con espacios sobrantes; se activa y se cambia el stock
        ProductoCatalogoDTO resultado = servicio.modificarProducto(ID_VENDEDOR, ID_PRODUCTO,
                new ProductoAltaDTO("  Lámpara LED  ", "  Lámpara de sobremesa con luz cálida.  ", 95.5,
                        List.of(ID_HOGAR, ID_ILUMINACION), 12, IMAGEN_NUEVA, true, 20.0, 5.0, null), null);

        // Then: se guardan los datos nuevos, limpios, sobre el mismo producto
        ArgumentCaptor<Producto> guardado = ArgumentCaptor.forClass(Producto.class);
        verify(repositorioProducto).save(guardado.capture());
        Producto producto = guardado.getValue();
        assertThat(producto.getId()).isEqualTo(ID_PRODUCTO);
        assertThat(producto.getIdVendedor()).isEqualTo(ID_VENDEDOR);
        assertThat(producto.getNombre()).isEqualTo("Lámpara LED");
        assertThat(producto.getDescripcion()).isEqualTo("Lámpara de sobremesa con luz cálida.");
        assertThat(producto.getIdCategorias()).containsExactly(ID_HOGAR, ID_ILUMINACION);
        assertThat(producto.getImagen()).isEqualTo(IMAGEN_NUEVA);
        assertThat(producto.getPrecio().getBase()).isEqualTo(95.5);
        assertThat(producto.getPrecio().getDescuento()).isEqualTo(20.0);
        assertThat(producto.getStock()).isEqualTo(12);
        assertThat(producto.getVisible()).isTrue();
        // El descuento premium no lo gestiona el vendedor: se conserva
        assertThat(producto.getPrecio().getDescuentoPremium()).isEqualTo(5.0);
        // Se devuelve como en el listado, con las categorias por nombre
        assertThat(resultado).isEqualTo(new ProductoCatalogoDTO(ID_PRODUCTO, "Lámpara LED",
                "Lámpara de sobremesa con luz cálida.", IMAGEN_NUEVA, List.of("Hogar", "Iluminación"), 95.5, 20.0,
                5.0, 12, true, false));
    }

    @Test
    void modificarProducto_desactivandoloYConStockCero_guardaElEstadoYElStockElegidos() {
        // Given: el producto esta activo y con 4 unidades
        Producto activo = lampara(ID_VENDEDOR);
        activo.cambiarVisibilidad(true);
        when(repositorioCategoria.findAllById(any())).thenReturn(List.of(HOGAR));
        when(repositorioProducto.findById(ID_PRODUCTO)).thenReturn(Optional.of(activo));
        when(repositorioProducto.save(any(Producto.class))).thenAnswer(invocacion -> invocacion.getArgument(0));

        // When
        servicio.modificarProducto(ID_VENDEDOR, ID_PRODUCTO, new ProductoAltaDTO("Lampara", "Lampara de sobremesa.",
                80.0, List.of(ID_HOGAR), 0, null, false, null, null, null), null);

        // Then
        ArgumentCaptor<Producto> guardado = ArgumentCaptor.forClass(Producto.class);
        verify(repositorioProducto).save(guardado.capture());
        assertThat(guardado.getValue().getVisible()).isFalse();
        assertThat(guardado.getValue().getStock()).isZero();
    }

    @Test
    void modificarProducto_sinActivoDescuentoNiImagen_conservaElEstadoYQuitaLaRebajaYLaFoto() {
        // Given: el producto guardado esta desactivado
        when(repositorioCategoria.findAllById(any())).thenReturn(List.of(HOGAR));
        when(repositorioProducto.findById(ID_PRODUCTO)).thenReturn(Optional.of(lampara(ID_VENDEDOR)));
        when(repositorioProducto.save(any(Producto.class))).thenAnswer(invocacion -> invocacion.getArgument(0));

        // When
        servicio.modificarProducto(ID_VENDEDOR, ID_PRODUCTO,
                cambios("Lampara", "Lampara de sobremesa.", 80.0, List.of(ID_HOGAR), 4), null);

        // Then
        ArgumentCaptor<Producto> guardado = ArgumentCaptor.forClass(Producto.class);
        verify(repositorioProducto).save(guardado.capture());
        assertThat(guardado.getValue().getVisible()).isFalse();
        assertThat(guardado.getValue().getPrecio().getDescuento()).isNull();
        assertThat(guardado.getValue().getImagen()).isNull();
    }

    static Stream<ProductoAltaDTO> faltaUnCampoObligatorio() {
        return Stream.of(
                cambios(null, "Descripción", 10.0, List.of(ID_HOGAR), 1),
                cambios("   ", "Descripción", 10.0, List.of(ID_HOGAR), 1),
                cambios("Lámpara", null, 10.0, List.of(ID_HOGAR), 1),
                cambios("Lámpara", "   ", 10.0, List.of(ID_HOGAR), 1),
                cambios("Lámpara", "Descripción", null, List.of(ID_HOGAR), 1),
                cambios("Lámpara", "Descripción", 10.0, null, 1),
                cambios("Lámpara", "Descripción", 10.0, List.of(), 1),
                cambios("Lámpara", "Descripción", 10.0, List.of(ID_HOGAR), null));
    }

    @ParameterizedTest
    @MethodSource("faltaUnCampoObligatorio")
    void modificarProducto_faltaUnCampoObligatorio_lanzaExcepcionConElMensajeYNoGuarda(ProductoAltaDTO datos) {
        // When / Then: se valida antes de buscar el producto
        assertThatThrownBy(() -> servicio.modificarProducto(ID_VENDEDOR, ID_PRODUCTO, datos, null))
                .isInstanceOf(ProductoInvalidoException.class)
                .hasMessage(ServicioProductoImpl.MENSAJE_CAMPOS_OBLIGATORIOS);
        verify(repositorioProducto, never()).findById(anyString());
        verify(repositorioProducto, never()).save(any());
    }

    static Stream<ProductoAltaDTO> precioOStockIncorrecto() {
        return Stream.of(
                cambios("Lámpara", "Descripción", 0.0, List.of(ID_HOGAR), 1),
                cambios("Lámpara", "Descripción", -5.0, List.of(ID_HOGAR), 1),
                cambios("Lámpara", "Descripción", 10.0, List.of(ID_HOGAR), -1));
    }

    @ParameterizedTest
    @MethodSource("precioOStockIncorrecto")
    void modificarProducto_precioOStockIncorrecto_lanzaExcepcionConElMensajeYNoGuarda(ProductoAltaDTO datos) {
        // When / Then
        assertThatThrownBy(() -> servicio.modificarProducto(ID_VENDEDOR, ID_PRODUCTO, datos, null))
                .isInstanceOf(ProductoInvalidoException.class)
                .hasMessage(ServicioProductoImpl.MENSAJE_VALORES_INCORRECTOS);
        verify(repositorioProducto, never()).save(any());
    }

    @Test
    void modificarProducto_algunaCategoriaNoExiste_lanzaExcepcionYNoGuarda() {
        // Given: de las dos categorias elegidas solo existe una
        when(repositorioCategoria.findAllById(any())).thenReturn(List.of(HOGAR));
        ProductoAltaDTO datos = cambios("Lámpara", "Descripción", 10.0, List.of(ID_HOGAR, ID_INEXISTENTE), 1);

        // When / Then
        assertThatThrownBy(() -> servicio.modificarProducto(ID_VENDEDOR, ID_PRODUCTO, datos, null))
                .isInstanceOf(ProductoInvalidoException.class)
                .hasMessage(ServicioProductoImpl.MENSAJE_VALORES_INCORRECTOS);
        verify(repositorioProducto, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Lámpara <b>", "Moda & Co", "Bolso$", "{\"$gt\": \"\"}"})
    void modificarProducto_nombreConCaracteresEspeciales_lanzaExcepcionYNoGuarda(String nombre) {
        // When / Then
        assertThatThrownBy(() -> servicio.modificarProducto(ID_VENDEDOR, ID_PRODUCTO,
                cambios(nombre, "Descripción", 10.0, List.of(ID_HOGAR), 1), null))
                .isInstanceOf(ProductoInvalidoException.class)
                .hasMessage(Constantes.MENSAJE_NOMBRE_NO_PERMITIDO);
        verify(repositorioProducto, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"<script>alert(1)</script>", "100% algodón", "Precio: $10"})
    void modificarProducto_descripcionConCaracteresEspeciales_lanzaExcepcionYNoGuarda(String descripcion) {
        // When / Then
        assertThatThrownBy(() -> servicio.modificarProducto(ID_VENDEDOR, ID_PRODUCTO,
                cambios("Lámpara", descripcion, 10.0, List.of(ID_HOGAR), 1), null))
                .isInstanceOf(ProductoInvalidoException.class)
                .hasMessage(Constantes.MENSAJE_DESCRIPCION_NO_PERMITIDA);
        verify(repositorioProducto, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(doubles = {-1.0, 100.5})
    void modificarProducto_descuentoFueraDeRango_lanzaExcepcionYNoGuarda(double descuento) {
        // Given
        ProductoAltaDTO datos = new ProductoAltaDTO("Lámpara", "Descripción", 10.0, List.of(ID_HOGAR), 1, null, true,
                descuento, null, null);

        // When / Then
        assertThatThrownBy(() -> servicio.modificarProducto(ID_VENDEDOR, ID_PRODUCTO, datos, null))
                .isInstanceOf(ProductoInvalidoException.class)
                .hasMessage(ServicioProductoImpl.MENSAJE_DESCUENTO_INVALIDO);
        verify(repositorioProducto, never()).save(any());
    }

    @Test
    void modificarProducto_productoDeOtroVendedor_lanzaNoEncontradoYNoGuarda() {
        // Given: datos validos, pero el producto es de otro vendedor
        when(repositorioCategoria.findAllById(any())).thenReturn(List.of(HOGAR));
        when(repositorioProducto.findById(ID_PRODUCTO)).thenReturn(Optional.of(lampara(ID_OTRO_VENDEDOR)));

        // When / Then: igual que si no existiera, para no revelar que existe
        assertThatThrownBy(() -> servicio.modificarProducto(ID_VENDEDOR, ID_PRODUCTO,
                cambios("Lámpara", "Descripción", 10.0, List.of(ID_HOGAR), 1), null))
                .isInstanceOf(ProductoNoEncontradoException.class)
                .hasMessage("El producto no existe en tu catálogo");
        verify(repositorioProducto, never()).save(any());
    }

    @Test
    void modificarProducto_productoInexistente_lanzaNoEncontradoYNoGuarda() {
        // Given
        when(repositorioCategoria.findAllById(any())).thenReturn(List.of(HOGAR));
        when(repositorioProducto.findById(ID_PRODUCTO)).thenReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> servicio.modificarProducto(ID_VENDEDOR, ID_PRODUCTO,
                cambios("Lámpara", "Descripción", 10.0, List.of(ID_HOGAR), 1), null))
                .isInstanceOf(ProductoNoEncontradoException.class);
        verify(repositorioProducto, never()).save(any());
    }
}
