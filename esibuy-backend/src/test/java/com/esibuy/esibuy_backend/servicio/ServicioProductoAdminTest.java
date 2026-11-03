package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import com.esibuy.esibuy_backend.dto.PaginaDTO;
import com.esibuy.esibuy_backend.dto.ProductoAdminDTO;
import com.esibuy.esibuy_backend.modelo.Categoria;
import com.esibuy.esibuy_backend.modelo.PerfilUsuario;
import com.esibuy.esibuy_backend.modelo.PrecioProducto;
import com.esibuy.esibuy_backend.modelo.Producto;
import com.esibuy.esibuy_backend.modelo.Rol;
import com.esibuy.esibuy_backend.modelo.Usuario;
import com.esibuy.esibuy_backend.repositorio.RepositorioCategoria;
import com.esibuy.esibuy_backend.repositorio.RepositorioUsuario;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ServicioProductoAdminTest {

    private static final String VENDEDOR = "64b7f0c2a1b2c3d4e5f60001";
    private static final String CATEGORIA = "64b7f0c2a1b2c3d4e5f60201";

    @Mock
    private MongoTemplate mongoTemplate;
    @Mock
    private RepositorioUsuario repositorioUsuario;
    @Mock
    private RepositorioCategoria repositorioCategoria;
    @InjectMocks
    private ServicioProductoAdmin servicio;

    private final ArgumentCaptor<Query> consulta = ArgumentCaptor.forClass(Query.class);

    private void hayProductos(List<Producto> productos, long total) {
        when(mongoTemplate.find(any(Query.class), eq(Producto.class))).thenReturn(productos);
        when(mongoTemplate.count(any(Query.class), eq(Producto.class))).thenReturn(total);
    }

    @Test
    void listar_noDescargaLasImagenesNiFiltraPorEstado() {
        hayProductos(List.of(), 0);

        servicio.listar(0, 20);

        verify(mongoTemplate).find(consulta.capture(), eq(Producto.class));
        assertThat(consulta.getValue().getFieldsObject()).containsEntry("imagen", 0);
        assertThat(consulta.getValue().getQueryObject()).isEmpty();
    }

    @Test
    void listar_tamanoExcesivo_seLimitaAlMaximo() {
        hayProductos(List.of(), 0);

        PaginaDTO<ProductoAdminDTO> pagina = servicio.listar(0, 10_000);

        verify(mongoTemplate).find(consulta.capture(), eq(Producto.class));
        assertThat(consulta.getValue().getLimit()).isEqualTo(ServicioProductoAdmin.TAMANO_MAXIMO);
        assertThat(pagina.tamano()).isEqualTo(ServicioProductoAdmin.TAMANO_MAXIMO);
    }

    @Test
    void listar_paginaNegativa_seTrataComoLaPrimera() {
        hayProductos(List.of(), 0);

        assertThat(servicio.listar(-3, 20).pagina()).isZero();
    }

    @Test
    void listar_productoDesactivado_apareceConVendedorYCategoria() {
        Producto producto = Producto.builder().id("p1").nombre("Auriculares").idVendedor(VENDEDOR)
                .idCategorias(List.of(CATEGORIA)).precio(new PrecioProducto(59.9, null, null))
                .stock(3).visible(false).build();
        Usuario vendedor = new Usuario("tienda@gmail.com", "$argon2id$hash", Rol.VENDEDOR,
                PerfilUsuario.builder().nombre("Ana").apellidos("Lopez").nombreComercial("Tienda Ana").build());
        ReflectionTestUtils.setField(vendedor, "id", VENDEDOR);
        hayProductos(List.of(producto), 1);
        when(repositorioUsuario.findAllById(any())).thenReturn(List.of(vendedor));
        when(repositorioCategoria.findAllById(any())).thenReturn(List.of(new Categoria(CATEGORIA, "Electronica", "")));

        ProductoAdminDTO fila = servicio.listar(0, 20).contenido().get(0);

        assertThat(fila.visible()).isFalse();
        assertThat(fila.vendedor()).isEqualTo("Tienda Ana");
        assertThat(fila.categorias()).containsExactly("Electronica");
        assertThat(fila.soloPremium()).isFalse(); // los productos antiguos no tienen el campo
    }
}