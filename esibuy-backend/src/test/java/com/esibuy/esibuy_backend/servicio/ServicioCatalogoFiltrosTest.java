package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.esibuy.esibuy_backend.dto.ProductoClienteDTO;
import com.esibuy.esibuy_backend.excepcion.FiltroNoValidoException;
import com.esibuy.esibuy_backend.modelo.PrecioProducto;
import com.esibuy.esibuy_backend.modelo.Producto;
import com.esibuy.esibuy_backend.modelo.Valoracion;
import com.esibuy.esibuy_backend.repositorio.RepositorioCategoria;
import com.esibuy.esibuy_backend.repositorio.RepositorioProducto;
import com.esibuy.esibuy_backend.repositorio.RepositorioValoracion;

/**
 * HU-20 Filtrar productos por categoria, precio, valoracion y disponibilidad. Los filtros se combinan entre si y con la
 * busqueda por nombre; si no tienen sentido, no se busca nada.
 */
@ExtendWith(MockitoExtension.class)
class ServicioCatalogoFiltrosTest {

    private static final String ID_TAZA = "64b7f0c2a1b2c3d4e5f60101";
    private static final String ID_VASO = "64b7f0c2a1b2c3d4e5f60102";
    private static final String ID_RELOJ = "64b7f0c2a1b2c3d4e5f60103";
    private static final String ID_COCINA = "64b7f0c2a1b2c3d4e5f60201";
    private static final String ID_MODA = "64b7f0c2a1b2c3d4e5f60202";
    private static final String ID_CLIENTE = "64b7f0c2a1b2c3d4e5f60001";
    private static final String ID_PEDIDO = "64b7f0c2a1b2c3d4e5f60301";

    @Mock
    private RepositorioProducto repositorioProducto;
    @Mock
    private RepositorioCategoria repositorioCategoria;
    @Mock
    private RepositorioValoracion repositorioValoracion;
    @InjectMocks
    private ServicioCatalogo servicio;

    // Taza: cocina, 10 €, con stock, 4,5 estrellas. Vaso: cocina, 20 € con un 50 % (10 €), agotado, sin valorar.
    // Reloj: moda, 100 € con un 20 % (80 €) y un 10 % mas para premium (72 €), poco stock, 2 estrellas.
    @BeforeEach
    void catalogo() {
        lenient().when(repositorioProducto.buscarActivos()).thenReturn(List.of(
                producto(ID_TAZA, "Taza", ID_COCINA, new PrecioProducto(10.0, null, null), 10),
                producto(ID_VASO, "Vaso", ID_COCINA, new PrecioProducto(20.0, 50.0, null), 0),
                producto(ID_RELOJ, "Reloj", ID_MODA, new PrecioProducto(100.0, 20.0, 10.0), 3)));
        lenient().when(repositorioValoracion.findAll()).thenReturn(List.of(
                new Valoracion(ID_TAZA, ID_CLIENTE, ID_PEDIDO, 5),
                new Valoracion(ID_TAZA, ID_CLIENTE, ID_PEDIDO, 4),
                new Valoracion(ID_RELOJ, ID_CLIENTE, ID_PEDIDO, 2)));
        lenient().when(repositorioProducto.findAllById(any())).thenReturn(List.of());
        lenient().when(repositorioCategoria.findAllById(any())).thenReturn(List.of());
    }

    private static Producto producto(String id, String nombre, String idCategoria, PrecioProducto precio, int stock) {
        return Producto.builder().id(id).nombre(nombre).idCategorias(List.of(idCategoria)).precio(precio)
                .stock(stock).visible(true).build();
    }

    private List<String> nombres(FiltroCatalogoCliente filtro, boolean clientePremium) {
        return servicio.buscar(filtro, 0, clientePremium).contenido().stream().map(ProductoClienteDTO::nombre).toList();
    }

    @Test
    void sinFiltros_salenTodosConSuValoracion() {
        List<ProductoClienteDTO> productos = servicio.buscar(FiltroCatalogoCliente.sinFiltros(), 0, false).contenido();

        assertThat(productos).extracting(ProductoClienteDTO::nombre).containsExactly("Reloj", "Vaso", "Taza");
        assertThat(productos.get(2).valoracionMedia()).isEqualTo(4.5);
        assertThat(productos.get(2).numeroValoraciones()).isEqualTo(2);
        assertThat(productos.get(1).valoracionMedia()).isNull();
        assertThat(productos.get(1).numeroValoraciones()).isZero();
    }

    @Test
    void porCategoria_soloLosDeEsaCategoria() {
        assertThat(nombres(new FiltroCatalogoCliente(null, ID_COCINA, null, null, null, false), false))
                .containsExactly("Vaso", "Taza");
    }

    @Test
    void porRangoDePrecio_comparaElPrecioConDescuentoYIncluyeLosLimites() {
        // El vaso cuesta 20 €, pero con su 50 % se paga a 10 €
        assertThat(nombres(new FiltroCatalogoCliente(null, null, 10.0, 10.0, null, false), false))
                .containsExactly("Vaso", "Taza");
    }

    @Test
    void porPrecio_alClientePremiumSeLeAplicaTambienSuDescuento() {
        FiltroCatalogoCliente hasta75 = new FiltroCatalogoCliente(null, null, null, 75.0, null, false);

        assertThat(nombres(hasta75, false)).doesNotContain("Reloj");
        assertThat(nombres(hasta75, true)).contains("Reloj");
    }

    @Test
    void porValoracion_soloLosQueTienenAlMenosEsaMedia() {
        assertThat(nombres(new FiltroCatalogoCliente(null, null, null, null, 4, false), false))
                .containsExactly("Taza");
    }

    @Test
    void porValoracion_losProductosSinValorarNoLaCumplen() {
        assertThat(nombres(new FiltroCatalogoCliente(null, null, null, null, 1, false), false))
                .containsExactly("Reloj", "Taza");
    }

    @Test
    void soloDisponibles_quitaLosAgotados() {
        assertThat(nombres(new FiltroCatalogoCliente(null, null, null, null, null, true), false))
                .containsExactly("Reloj", "Taza");
    }

    @Test
    void variosFiltrosALaVez_tienenQueCumplirseTodos() {
        // Cocina, con stock y la busqueda «ta»
        assertThat(nombres(new FiltroCatalogoCliente("ta", ID_COCINA, null, null, null, true), false))
                .containsExactly("Taza");
    }

    @Test
    void sinCoincidencias_devuelvePaginaVacia() {
        assertThat(servicio.buscar(new FiltroCatalogoCliente(null, ID_MODA, null, null, 4, false), 0, false)
                .totalElementos()).isZero();
    }

    @Test
    void precioMinimoMayorQueElMaximo_lanzaFiltroNoValidoSinConsultarNada() {
        FiltroCatalogoCliente filtro = new FiltroCatalogoCliente(null, null, 20.0, 10.0, null, false);

        assertThatThrownBy(() -> servicio.buscar(filtro, 0, false))
                .isInstanceOf(FiltroNoValidoException.class)
                .hasMessage(FiltroCatalogoCliente.MENSAJE_RANGO_PRECIO);
        verify(repositorioProducto, never()).buscarActivos();
    }

    @Test
    void precioNegativo_lanzaFiltroNoValido() {
        assertThatThrownBy(() -> servicio.buscar(new FiltroCatalogoCliente(null, null, -1.0, null, null, false), 0,
                false)).isInstanceOf(FiltroNoValidoException.class)
                .hasMessage(FiltroCatalogoCliente.MENSAJE_PRECIO_NEGATIVO);
    }

    @Test
    void valoracionFueraDe1a5_lanzaFiltroNoValido() {
        assertThatThrownBy(() -> servicio.buscar(new FiltroCatalogoCliente(null, null, null, null, 6, false), 0,
                false)).isInstanceOf(FiltroNoValidoException.class)
                .hasMessage(FiltroCatalogoCliente.MENSAJE_VALORACION);
    }
}
