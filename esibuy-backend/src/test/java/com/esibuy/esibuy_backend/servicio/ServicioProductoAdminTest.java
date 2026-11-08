package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.util.ReflectionTestUtils;

import com.esibuy.esibuy_backend.dto.PaginaDTO;
import com.esibuy.esibuy_backend.dto.ProductoAdminDTO;
import com.esibuy.esibuy_backend.dto.ProductoAdminDetalleDTO;
import com.esibuy.esibuy_backend.excepcion.FiltroNoValidoException;
import com.esibuy.esibuy_backend.excepcion.ProductoNoEncontradoException;
import com.esibuy.esibuy_backend.modelo.Categoria;
import com.esibuy.esibuy_backend.modelo.PerfilUsuario;
import com.esibuy.esibuy_backend.modelo.PrecioProducto;
import com.esibuy.esibuy_backend.modelo.Producto;
import com.esibuy.esibuy_backend.modelo.Rol;
import com.esibuy.esibuy_backend.modelo.Usuario;
import com.esibuy.esibuy_backend.modelo.Valoracion;
import com.esibuy.esibuy_backend.repositorio.RepositorioCategoria;
import com.esibuy.esibuy_backend.repositorio.RepositorioProducto;
import com.esibuy.esibuy_backend.repositorio.RepositorioUsuario;
import com.esibuy.esibuy_backend.repositorio.RepositorioValoracion;

@ExtendWith(MockitoExtension.class)
class ServicioProductoAdminTest {

    private static final String VENDEDOR = "64b7f0c2a1b2c3d4e5f60001";
    private static final String CATEGORIA = "64b7f0c2a1b2c3d4e5f60201";
    private static final FiltroCatalogoCliente SIN_FILTROS = FiltroCatalogoCliente.sinFiltros();

    @Mock
    private MongoTemplate mongoTemplate;
    @Mock
    private RepositorioUsuario repositorioUsuario;
    @Mock
    private RepositorioCategoria repositorioCategoria;
    @Mock
    private RepositorioProducto repositorioProducto;
    @Mock
    private RepositorioValoracion repositorioValoracion;
    @InjectMocks
    private ServicioProductoAdmin servicio;

    private final ArgumentCaptor<Query> consulta = ArgumentCaptor.forClass(Query.class);

    private void hayProductos(List<Producto> productos) {
        when(mongoTemplate.find(any(Query.class), eq(Producto.class))).thenReturn(productos);
        lenient().when(repositorioProducto.findAllById(anyCollection())).thenReturn(productos);
    }

    private static Producto producto(String id, String nombre, Double precio, int stock) {
        return Producto.builder().id(id).nombre(nombre).precio(new PrecioProducto(precio, null, null)).stock(stock)
                .visible(true).build();
    }

    private static List<String> nombres(PaginaDTO<ProductoAdminDTO> pagina) {
        return pagina.contenido().stream().map(ProductoAdminDTO::nombre).toList();
    }

    @Test
    void listar_noDescargaTodasLasImagenesNiFiltraPorEstado() {
        hayProductos(List.of());

        servicio.listar(SIN_FILTROS, 0, 20);

        verify(mongoTemplate).find(consulta.capture(), eq(Producto.class));
        assertThat(consulta.getValue().getFieldsObject()).containsEntry("imagen", 0);
        assertThat(consulta.getValue().getQueryObject()).isEmpty();
    }

    @Test
    void listar_tamanoExcesivo_seLimitaAlMaximo() {
        hayProductos(List.of());

        assertThat(servicio.listar(SIN_FILTROS, 0, 10_000).tamano()).isEqualTo(ServicioProductoAdmin.TAMANO_MAXIMO);
    }

    @Test
    void listar_paginaNegativa_seTrataComoLaPrimera() {
        hayProductos(List.of());

        assertThat(servicio.listar(SIN_FILTROS, -3, 20).pagina()).isZero();
    }

    @Test
    void listar_productoDesactivado_apareceConVendedorCategoriaFotoYValoracion() {
        Producto producto = Producto.builder().id("p1").nombre("Auriculares").idVendedor(VENDEDOR)
                .idCategorias(List.of(CATEGORIA)).imagen("data:image/png;base64,AAAA")
                .precio(new PrecioProducto(50.0, 10.0, null)).stock(3).visible(false).build();
        Usuario vendedor = new Usuario("tienda@gmail.com", "$argon2id$hash", Rol.VENDEDOR,
                PerfilUsuario.builder().nombre("Ana").apellidos("Lopez").nombreComercial("Tienda Ana").build());
        ReflectionTestUtils.setField(vendedor, "id", VENDEDOR);
        hayProductos(List.of(producto));
        when(repositorioUsuario.findAllById(any())).thenReturn(List.of(vendedor));
        when(repositorioCategoria.findAllById(any())).thenReturn(List.of(new Categoria(CATEGORIA, "Electronica", "")));
        when(repositorioValoracion.findAll()).thenReturn(List.of(new Valoracion("p1", "c1", "o1", 4),
                new Valoracion("p1", "c2", "o2", 5)));

        ProductoAdminDTO fila = servicio.listar(SIN_FILTROS, 0, 20).contenido().get(0);

        assertThat(fila.visible()).isFalse();
        assertThat(fila.vendedor()).isEqualTo("Tienda Ana");
        assertThat(fila.categorias()).containsExactly("Electronica");
        assertThat(fila.soloPremium()).isFalse(); // los productos antiguos no tienen el campo
        assertThat(fila.imagen()).isEqualTo("data:image/png;base64,AAAA");
        assertThat(fila.precioFinal()).isEqualTo(45.0);
        assertThat(fila.valoracionMedia()).isEqualTo(4.5);
        assertThat(fila.numeroValoraciones()).isEqualTo(2);
    }

    @Test
    void listar_aplicaBusquedaPrecioYDisponibilidad() {
        hayProductos(List.of(producto("p1", "Lámpara barata", 10.0, 5), producto("p2", "Lámpara cara", 90.0, 5),
                producto("p3", "Lámpara agotada", 10.0, 0), producto("p4", "Mesa", 10.0, 5)));
        FiltroCatalogoCliente filtro = new FiltroCatalogoCliente("lampara", null, null, 50.0, null, true);

        PaginaDTO<ProductoAdminDTO> pagina = servicio.listar(filtro, 0, 12);

        assertThat(nombres(pagina)).containsExactly("Lámpara barata");
        assertThat(pagina.totalElementos()).isEqualTo(1);
    }

    @Test
    void listar_buscaTambienPorNombreDeLaTienda() {
        Producto deLaTienda = Producto.builder().id("p1").nombre("Taza").idVendedor(VENDEDOR)
                .precio(new PrecioProducto(10.0, null, null)).stock(5).visible(true).build();
        Usuario vendedor = new Usuario("tienda@gmail.com", "$argon2id$hash", Rol.VENDEDOR,
                PerfilUsuario.builder().nombre("Ana").apellidos("Lopez").nombreComercial("Cerámica Ana").build());
        ReflectionTestUtils.setField(vendedor, "id", VENDEDOR);
        hayProductos(List.of(deLaTienda, producto("p2", "Plato", 10.0, 5)));
        when(repositorioUsuario.findAllById(any())).thenReturn(List.of(vendedor));

        PaginaDTO<ProductoAdminDTO> pagina = servicio.listar(new FiltroCatalogoCliente("ceramica", null, null, null, null, false), 0, 12);

        assertThat(nombres(pagina)).containsExactly("Taza");
        assertThat(pagina.contenido().get(0).vendedor()).isEqualTo("Cerámica Ana");
    }

    @Test
    void listar_filtraPorValoracionMinima() {
        hayProductos(List.of(producto("p1", "Bien valorado", 10.0, 5), producto("p2", "Mal valorado", 10.0, 5),
                producto("p3", "Sin valorar", 10.0, 5)));
        when(repositorioValoracion.findAll()).thenReturn(List.of(new Valoracion("p1", "c1", "o1", 5),
                new Valoracion("p2", "c1", "o2", 2)));

        PaginaDTO<ProductoAdminDTO> pagina = servicio.listar(new FiltroCatalogoCliente(null, null, null, null, 4, false), 0, 12);

        assertThat(nombres(pagina)).containsExactly("Bien valorado");
    }

    @Test
    void listar_paginaSobreLosProductosFiltrados() {
        hayProductos(List.of(producto("p1", "A", 10.0, 5), producto("p2", "B", 10.0, 5), producto("p3", "C", 10.0, 5)));

        PaginaDTO<ProductoAdminDTO> pagina = servicio.listar(SIN_FILTROS, 1, 2);

        assertThat(nombres(pagina)).containsExactly("C");
        assertThat(pagina.totalElementos()).isEqualTo(3);
        assertThat(pagina.totalPaginas()).isEqualTo(2);
    }

    @Test
    void obtener_productoDesactivado_devuelveSuFichaConPreciosYValoracion() {
        String id = "64b7f0c2a1b2c3d4e5f60301";
        Producto producto = Producto.builder().id(id).nombre("Auriculares").descripcion("Inalambricos")
                .precio(new PrecioProducto(100.0, 10.0, 15.0)).stock(0).visible(false).build();
        when(repositorioProducto.findById(id)).thenReturn(Optional.of(producto));
        when(repositorioValoracion.buscarPorProducto(new ObjectId(id))).thenReturn(
                List.of(new Valoracion(id, "c1", "o1", 3), new Valoracion(id, "c2", "o2", 4)));

        ProductoAdminDetalleDTO ficha = servicio.obtener(id);

        assertThat(ficha.visible()).isFalse();
        assertThat(ficha.descripcion()).isEqualTo("Inalambricos");
        assertThat(ficha.vendedor()).isEqualTo(ServicioProductoAdmin.VENDEDOR_DESCONOCIDO);
        assertThat(ficha.precioFinal()).isEqualTo(90.0);
        assertThat(ficha.precioPremium()).isEqualTo(75.0);
        assertThat(ficha.valoracionMedia()).isEqualTo(3.5);
        assertThat(ficha.numeroValoraciones()).isEqualTo(2);
    }

    @Test
    void obtener_sinValoraciones_mediaVacia() {
        String id = "64b7f0c2a1b2c3d4e5f60301";
        when(repositorioProducto.findById(id)).thenReturn(Optional.of(producto(id, "Taza", 5.0, 1)));

        assertThat(servicio.obtener(id).valoracionMedia()).isNull();
    }

    @Test
    void obtener_inexistenteOIdNoValido_lanzaNoEncontrado() {
        String id = "64b7f0c2a1b2c3d4e5f60301";
        when(repositorioProducto.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.obtener(id)).isInstanceOf(ProductoNoEncontradoException.class);
        assertThatThrownBy(() -> servicio.obtener("no-es-un-id")).isInstanceOf(ProductoNoEncontradoException.class);
    }

    @Test
    void listar_filtrosSinSentido_lanzaExcepcion() {
        FiltroCatalogoCliente filtro = new FiltroCatalogoCliente(null, null, 50.0, 10.0, null, false);

        assertThatThrownBy(() -> servicio.listar(filtro, 0, 12)).isInstanceOf(FiltroNoValidoException.class);
    }
}
