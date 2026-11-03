package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.esibuy.esibuy_backend.dto.ProductoAltaDTO;
import com.esibuy.esibuy_backend.excepcion.ProductoInvalidoException;
import com.esibuy.esibuy_backend.modelo.Categoria;
import com.esibuy.esibuy_backend.modelo.Producto;
import com.esibuy.esibuy_backend.repositorio.RepositorioCategoria;
import com.esibuy.esibuy_backend.repositorio.RepositorioProducto;

/**
 * Limites que el backend comprueba en el alta y en la modificacion de productos, los mismos que el formulario del
 * frontend: longitud del nombre y la descripcion, formato y tamano de la imagen y stock maximo. Asi tampoco se pueden
 * saltar llamando directamente a la API.
 */
@ExtendWith(MockitoExtension.class)
class ServicioProductoLimitesTest {

    private static final String ID_VENDEDOR = "64b7f0c2a1b2c3d4e5f60001";
    private static final String ID_PRODUCTO = "64b7f0c2a1b2c3d4e5f60101";
    private static final String ID_HOGAR = "64b7f0c2a1b2c3d4e5f60202";
    // 1 MB exacto: 1.048.576 bytes son 1.398.104 caracteres en base64 (con dos "=" de relleno)
    private static final String BASE64_1MB = "A".repeat(1_398_102) + "==";
    // Un byte mas que 1 MB
    private static final String BASE64_1MB_Y_UN_BYTE = "A".repeat(1_398_103) + "=";

    @Mock
    private RepositorioProducto repositorioProducto;
    @Mock
    private RepositorioCategoria repositorioCategoria;
    @InjectMocks
    private ServicioProductoImpl servicio;

    private static ProductoAltaDTO producto(String nombre, String descripcion, String imagen, int stock) {
        return new ProductoAltaDTO(nombre, descripcion, 10.0, List.of(ID_HOGAR), stock, imagen, null, null, null, null);
    }

    private void altaSeGuarda() {
        when(repositorioCategoria.findAllById(any()))
                .thenReturn(List.of(new Categoria(ID_HOGAR, "Hogar", "Muebles y decoración.")));
        when(repositorioProducto.save(any(Producto.class))).thenAnswer(invocacion -> invocacion.getArgument(0));
    }

    private void noSeGuarda(ProductoAltaDTO datos, String mensaje) {
        assertThatThrownBy(() -> servicio.crearProducto(ID_VENDEDOR, datos))
                .isInstanceOf(ProductoInvalidoException.class)
                .hasMessage(mensaje);
        verify(repositorioProducto, never()).save(any());
    }

    @Test
    void crearProducto_nombreYDescripcionEnElLimite_seGuardan() {
        // Given
        altaSeGuarda();

        // When
        servicio.crearProducto(ID_VENDEDOR, producto("N".repeat(100), "D".repeat(2000), null, 1));

        // Then
        ArgumentCaptor<Producto> guardado = ArgumentCaptor.forClass(Producto.class);
        verify(repositorioProducto).save(guardado.capture());
        assertThat(guardado.getValue().getNombre()).hasSize(100);
        assertThat(guardado.getValue().getDescripcion()).hasSize(2000);
    }

    @Test
    void crearProducto_nombreDeMasDe100Caracteres_lanzaExcepcionYNoGuarda() {
        noSeGuarda(producto("N".repeat(101), "Descripción", null, 1), ServicioProductoImpl.MENSAJE_NOMBRE_LARGO);
    }

    @Test
    void crearProducto_descripcionDeMasDe2000Caracteres_lanzaExcepcionYNoGuarda() {
        noSeGuarda(producto("Lampara", "D".repeat(2001), null, 1), ServicioProductoImpl.MENSAJE_DESCRIPCION_LARGA);
    }

    @ParameterizedTest
    @ValueSource(strings = {"data:image/jpeg;base64,/9j/4AAQSkZJRg==", "data:image/png;base64,iVBORw0KGgo=",
            "data:image/webp;base64,UklGRhYAAABXRUJQ"})
    void crearProducto_imagenJpgPngOWebp_seGuarda(String imagen) {
        // Given
        altaSeGuarda();

        // When
        servicio.crearProducto(ID_VENDEDOR, producto("Lampara", "Descripción", imagen, 1));

        // Then
        ArgumentCaptor<Producto> guardado = ArgumentCaptor.forClass(Producto.class);
        verify(repositorioProducto).save(guardado.capture());
        assertThat(guardado.getValue().getImagen()).isEqualTo(imagen);
    }

    @Test
    void crearProducto_imagenDeJusto1MB_seGuarda() {
        // Given
        altaSeGuarda();

        String imagen = "data:image/png;base64," + BASE64_1MB;

        // When
        servicio.crearProducto(ID_VENDEDOR, producto("Lampara", "Descripción", imagen, 1));

        // Then
        verify(repositorioProducto).save(any(Producto.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "data:image/gif;base64,R0lGODlhAQABAAAAACw=",
            "data:image/svg+xml;base64,PHN2Zz48L3N2Zz4=",
            "https://otra-web.com/foto.jpg",
            "javascript:alert(1)",
            "data:image/png;base64,",
            "data:image/png;base64,esto no es base64",
            "data:image/png;base64,iVBORw0KGgo"})
    void crearProducto_imagenQueNoEsJpgPngNiWebpEnBase64_lanzaExcepcionYNoGuarda(String imagen) {
        noSeGuarda(producto("Lampara", "Descripción", imagen, 1), ServicioProductoImpl.MENSAJE_IMAGEN_INVALIDA);
    }

    @Test
    void crearProducto_imagenDeMasDe1MB_lanzaExcepcionYNoGuarda() {
        noSeGuarda(producto("Lampara", "Descripción", "data:image/png;base64," + BASE64_1MB_Y_UN_BYTE, 1),
                ServicioProductoImpl.MENSAJE_IMAGEN_INVALIDA);
    }

    @Test
    void crearProducto_stockPorEncimaDelMaximo_lanzaExcepcionYNoGuarda() {
        noSeGuarda(producto("Lampara", "Descripción", null, ServicioProductoImpl.STOCK_MAXIMO + 1),
                ServicioProductoImpl.MENSAJE_STOCK_MAXIMO);
    }

    @Test
    void crearProducto_stockJustoElMaximo_seGuarda() {
        // Given
        altaSeGuarda();

        // When
        servicio.crearProducto(ID_VENDEDOR,
                producto("Lampara", "Descripción", null, ServicioProductoImpl.STOCK_MAXIMO));

        // Then
        verify(repositorioProducto).save(any(Producto.class));
    }

    @Test
    void modificarProducto_aplicaLosMismosLimites() {
        // When / Then: la validacion es comun, asi que la modificacion tampoco acepta una imagen no valida
        assertThatThrownBy(() -> servicio.modificarProducto(ID_VENDEDOR, ID_PRODUCTO,
                producto("Lampara", "Descripción", "https://otra-web.com/foto.jpg", 1), null))
                .isInstanceOf(ProductoInvalidoException.class)
                .hasMessage(ServicioProductoImpl.MENSAJE_IMAGEN_INVALIDA);
        verify(repositorioProducto, never()).save(any());
    }
}
