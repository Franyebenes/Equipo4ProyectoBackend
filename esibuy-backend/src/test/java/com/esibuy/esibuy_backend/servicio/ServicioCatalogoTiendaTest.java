package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.esibuy.esibuy_backend.dto.ProductoClienteDTO;
import com.esibuy.esibuy_backend.modelo.PerfilUsuario;
import com.esibuy.esibuy_backend.modelo.PrecioProducto;
import com.esibuy.esibuy_backend.modelo.Producto;
import com.esibuy.esibuy_backend.modelo.Rol;
import com.esibuy.esibuy_backend.modelo.Usuario;
import com.esibuy.esibuy_backend.repositorio.RepositorioCategoria;
import com.esibuy.esibuy_backend.repositorio.RepositorioProducto;
import com.esibuy.esibuy_backend.repositorio.RepositorioUsuario;
import com.esibuy.esibuy_backend.repositorio.RepositorioValoracion;

/** El catalogo muestra la tienda de cada producto y la busqueda encuentra tambien por nombre de tienda. */
@ExtendWith(MockitoExtension.class)
class ServicioCatalogoTiendaTest {

    private static final String VENDEDOR = "64b7f0c2a1b2c3d4e5f60001";
    private static final String OTRO_VENDEDOR = "64b7f0c2a1b2c3d4e5f60002";

    @Mock
    private RepositorioProducto repositorioProducto;
    @Mock
    private RepositorioCategoria repositorioCategoria;
    @Mock
    private RepositorioValoracion repositorioValoracion;
    @Mock
    private RepositorioUsuario repositorioUsuario;
    @InjectMocks
    private ServicioCatalogo servicio;

    private static Producto producto(String id, String nombre, String idVendedor) {
        return Producto.builder().id(id).nombre(nombre).idVendedor(idVendedor)
                .precio(new PrecioProducto(10.0, null, null)).stock(5).visible(true).build();
    }

    private static Usuario vendedor(String id, String tienda) {
        Usuario usuario = new Usuario(id + "@gmail.com", "$argon2id$hash", Rol.VENDEDOR,
                PerfilUsuario.builder().nombre("Ana").apellidos("Lopez").nombreComercial(tienda).build());
        ReflectionTestUtils.setField(usuario, "id", id);
        return usuario;
    }

    @BeforeEach
    void hayProductosDeDosTiendas() {
        List<Producto> productos = List.of(producto("p2", "Plato", OTRO_VENDEDOR), producto("p1", "Taza", VENDEDOR));
        when(repositorioProducto.buscarActivos()).thenReturn(productos);
        lenient().when(repositorioProducto.findAllById(any())).thenReturn(productos);
        when(repositorioUsuario.findAllById(any()))
                .thenReturn(List.of(vendedor(VENDEDOR, "Cerámica Ana"), vendedor(OTRO_VENDEDOR, "Casa Luis")));
    }

    private List<ProductoClienteDTO> buscar(String busqueda) {
        return servicio.buscar(new FiltroCatalogoCliente(busqueda, null, null, null, null, false), 0, false).contenido();
    }

    @Test
    void cadaProductoLlevaElNombreDeSuTienda() {
        assertThat(buscar(null)).extracting(ProductoClienteDTO::nombre, ProductoClienteDTO::nombreTienda)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("Taza", "Cerámica Ana"),
                        org.assertj.core.groups.Tuple.tuple("Plato", "Casa Luis"));
    }

    @Test
    void buscarPorNombreDeTienda_sinDistinguirMayusculasNiTildes_encuentraSusProductos() {
        assertThat(buscar("CERAMICA")).extracting(ProductoClienteDTO::nombre).containsExactly("Taza");
    }

    @Test
    void buscarPorNombreDeProducto_sigueFuncionando() {
        assertThat(buscar("plato")).extracting(ProductoClienteDTO::nombre).containsExactly("Plato");
    }

    @Test
    void buscarAlgoQueNoEsProductoNiTienda_noDevuelveNada() {
        assertThat(buscar("zapatos")).isEmpty();
    }
}
