package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.esibuy.esibuy_backend.dto.Disponibilidad;
import com.esibuy.esibuy_backend.dto.PaginaDTO;
import com.esibuy.esibuy_backend.dto.ProductoClienteDTO;
import com.esibuy.esibuy_backend.modelo.Categoria;
import com.esibuy.esibuy_backend.modelo.PrecioProducto;
import com.esibuy.esibuy_backend.modelo.Producto;
import com.esibuy.esibuy_backend.repositorio.RepositorioCategoria;
import com.esibuy.esibuy_backend.repositorio.RepositorioProducto;

/**
 * HU-19 Buscar productos: catalogo del cliente con los productos activos, los mas nuevos primero y de 12 en 12;
 * busqueda por nombre completo o parcial; disponibilidad sin stock exacto y precio segun el tipo de cliente.
 */
@ExtendWith(MockitoExtension.class)
class ServicioCatalogoTest {

    // Los ids de MongoDB crecen con la fecha de creacion: ...0103 es el mas nuevo
    private static final String ID_ANTIGUO = "64b7f0c2a1b2c3d4e5f60101";
    private static final String ID_MEDIO = "64b7f0c2a1b2c3d4e5f60102";
    private static final String ID_NUEVO = "64b7f0c2a1b2c3d4e5f60103";
    private static final String ID_HOGAR = "64b7f0c2a1b2c3d4e5f60201";
    private static final String IMAGEN = "data:image/png;base64,iVBORw0KGgo=";

    @Mock
    private RepositorioProducto repositorioProducto;
    @Mock
    private RepositorioCategoria repositorioCategoria;
    @InjectMocks
    private ServicioCatalogo servicio;

    private static Producto producto(String id, String nombre, int stock) {
        return Producto.builder()
                .id(id)
                .nombre(nombre)
                .idCategorias(List.of(ID_HOGAR))
                .precio(new PrecioProducto(10.0, null, null))
                .stock(stock)
                .visible(true)
                .build();
    }

    @Test
    void buscar_sinBusqueda_devuelveLosActivosLosMasNuevosPrimeroConCategoriaEImagen() {
        // Given: MongoDB los devuelve sin orden; solo el nuevo tiene imagen
        when(repositorioProducto.buscarActivos()).thenReturn(List.of(
                producto(ID_ANTIGUO, "Taza", 10), producto(ID_NUEVO, "Lámpara", 10), producto(ID_MEDIO, "Vaso", 10)));
        Producto conImagen = Producto.builder().id(ID_NUEVO).nombre("Lámpara").imagen(IMAGEN).build();
        when(repositorioProducto.findAllById(any())).thenReturn(List.of(conImagen));
        when(repositorioCategoria.findAllById(any()))
                .thenReturn(List.of(new Categoria(ID_HOGAR, "Hogar", "Muebles y decoración.")));

        // When
        PaginaDTO<ProductoClienteDTO> pagina = servicio.buscar(null, 0, false);

        // Then
        assertThat(pagina.contenido()).extracting(ProductoClienteDTO::nombre)
                .containsExactly("Lámpara", "Vaso", "Taza");
        assertThat(pagina.contenido().get(0).categorias()).containsExactly("Hogar");
        assertThat(pagina.contenido().get(0).imagen()).isEqualTo(IMAGEN);
        assertThat(pagina.contenido().get(1).imagen()).isNull();
        assertThat(pagina.totalElementos()).isEqualTo(3);
        assertThat(pagina.totalPaginas()).isEqualTo(1);
    }

    @Test
    void buscar_conParteDelNombreSinTildesNiMayusculas_devuelveSoloLosQueCoinciden() {
        // Given
        when(repositorioProducto.buscarActivos()).thenReturn(List.of(
                producto(ID_ANTIGUO, "Lámpara de pie", 10), producto(ID_MEDIO, "Bolso de piel", 10)));

        // When: «LAMPA» encuentra «Lámpara de pie»
        PaginaDTO<ProductoClienteDTO> pagina = servicio.buscar("LAMPA", 0, false);

        // Then
        assertThat(pagina.contenido()).extracting(ProductoClienteDTO::nombre).containsExactly("Lámpara de pie");
        assertThat(pagina.totalElementos()).isEqualTo(1);
    }

    @Test
    void buscar_sinCoincidencias_devuelvePaginaVaciaSinPedirImagenesNiCategorias() {
        // Given
        when(repositorioProducto.buscarActivos()).thenReturn(List.of(producto(ID_ANTIGUO, "Taza", 10)));

        // When
        PaginaDTO<ProductoClienteDTO> pagina = servicio.buscar("bicicleta", 0, false);

        // Then: el frontend muestra el mensaje de que no hay resultados
        assertThat(pagina.contenido()).isEmpty();
        assertThat(pagina.totalElementos()).isZero();
        verify(repositorioProducto, never()).findAllById(any());
        verify(repositorioCategoria, never()).findAllById(any());
    }

    @Test
    void buscar_conMasDeUnaPagina_devuelveLaPedidaDe12En12() {
        // Given: 14 productos sin categorias ni imagen
        List<Producto> productos = new ArrayList<>();
        for (int i = 10; i < 24; i++) {
            productos.add(Producto.builder().id("64b7f0c2a1b2c3d4e5f601" + i).nombre("Producto " + i)
                    .precio(new PrecioProducto(10.0, null, null)).stock(10).visible(true).build());
        }
        when(repositorioProducto.buscarActivos()).thenReturn(productos);
        when(repositorioProducto.findAllById(any())).thenReturn(List.of());

        // When
        PaginaDTO<ProductoClienteDTO> primera = servicio.buscar("", 0, false);
        PaginaDTO<ProductoClienteDTO> segunda = servicio.buscar("", 1, false);

        // Then: la segunda pagina tiene los 2 mas antiguos
        assertThat(primera.contenido()).hasSize(ServicioCatalogo.TAMANO_PAGINA);
        assertThat(primera.contenido().get(0).nombre()).isEqualTo("Producto 23");
        assertThat(segunda.contenido()).extracting(ProductoClienteDTO::nombre)
                .containsExactly("Producto 11", "Producto 10");
        assertThat(segunda.pagina()).isEqualTo(1);
        assertThat(segunda.totalPaginas()).isEqualTo(2);
        assertThat(segunda.totalElementos()).isEqualTo(14);
    }

    @Test
    void buscar_paginaNegativa_devuelveLaPrimera() {
        // Given
        when(repositorioProducto.buscarActivos()).thenReturn(List.of(producto(ID_ANTIGUO, "Taza", 10)));
        when(repositorioProducto.findAllById(any())).thenReturn(List.of());
        when(repositorioCategoria.findAllById(any())).thenReturn(List.of());

        // When
        PaginaDTO<ProductoClienteDTO> pagina = servicio.buscar(null, -3, false);

        // Then
        assertThat(pagina.pagina()).isZero();
        assertThat(pagina.contenido()).hasSize(1);
    }

    @Test
    void buscar_devuelveLaDisponibilidadSinElStockExacto() {
        // Given
        when(repositorioProducto.buscarActivos()).thenReturn(List.of(
                producto(ID_NUEVO, "Agotado", 0), producto(ID_MEDIO, "Pocas", 4), producto(ID_ANTIGUO, "Muchas", 5)));
        when(repositorioProducto.findAllById(any())).thenReturn(List.of());
        when(repositorioCategoria.findAllById(any())).thenReturn(List.of());

        // When
        PaginaDTO<ProductoClienteDTO> pagina = servicio.buscar(null, 0, false);

        // Then
        assertThat(pagina.contenido()).extracting(ProductoClienteDTO::disponibilidad)
                .containsExactly(Disponibilidad.AGOTADO, Disponibilidad.POCO_STOCK, Disponibilidad.DISPONIBLE);
    }

    @Test
    void buscar_precioFinal_soloElClientePremiumTieneLosDosDescuentos() {
        // Given: 100 € con un 20 % para todos y un 10 % mas para premium; solo para premium
        Producto producto = Producto.builder().id(ID_ANTIGUO).nombre("Reloj")
                .precio(new PrecioProducto(100.0, 20.0, 10.0)).stock(10).visible(true).soloPremium(true).build();
        when(repositorioProducto.buscarActivos()).thenReturn(List.of(producto));
        when(repositorioProducto.findAllById(any())).thenReturn(List.of());

        // When
        ProductoClienteDTO paraEstandar = servicio.buscar(null, 0, false).contenido().get(0);
        ProductoClienteDTO paraPremium = servicio.buscar(null, 0, true).contenido().get(0);

        // Then: el estandar tambien lo ve, marcado como solo premium
        assertThat(paraEstandar.precio()).isEqualTo(100.0);
        assertThat(paraEstandar.precioFinal()).isEqualTo(80.0);
        assertThat(paraPremium.precioFinal()).isEqualTo(72.0);
        assertThat(paraEstandar.soloPremium()).isTrue();
    }
}  

