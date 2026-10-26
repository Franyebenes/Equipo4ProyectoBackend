package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;

import com.esibuy.esibuy_backend.dto.CarritoDTO;
import com.esibuy.esibuy_backend.modelo.Carrito;
import com.esibuy.esibuy_backend.modelo.PerfilUsuario;
import com.esibuy.esibuy_backend.modelo.PrecioProducto;
import com.esibuy.esibuy_backend.modelo.Producto;
import com.esibuy.esibuy_backend.modelo.Usuario;
import com.esibuy.esibuy_backend.repositorio.RepositorioCarrito;
import com.esibuy.esibuy_backend.repositorio.RepositorioProducto;
import com.esibuy.esibuy_backend.repositorio.RepositorioUsuario;

/** Cada linea del carrito dice de que vendedor es, para agruparlo por vendedor y comprar de uno en uno. */
@ExtendWith(MockitoExtension.class)
class ServicioCarritoVendedorTest {

    private static final String ID_CLIENTE = "64b000000000000000000001";
    private static final String ID_TECLADO = "64b000000000000000000002";
    private static final String ID_RATON = "64b000000000000000000003";
    private static final String VENDEDOR_A = "64b0000000000000000000a1";
    private static final String VENDEDOR_B = "64b0000000000000000000b2";

    @Mock
    private RepositorioCarrito repositorioCarrito;
    @Mock
    private RepositorioProducto repositorioProducto;
    @Mock
    private MongoTemplate mongoTemplate;
    @Mock
    private RepositorioUsuario repositorioUsuario;
    private ServicioCarrito servicio;

    @BeforeEach
    void crearServicio() {
        servicio = new ServicioCarrito(repositorioCarrito, repositorioProducto, mongoTemplate, repositorioUsuario);
        Carrito carrito = new Carrito(ID_CLIENTE);
        carrito.anadir(ID_TECLADO, 1);
        carrito.anadir(ID_RATON, 1);
        when(repositorioCarrito.buscarPorCliente(new ObjectId(ID_CLIENTE))).thenReturn(Optional.of(carrito));
        Producto teclado = Producto.builder().id(ID_TECLADO).idVendedor(VENDEDOR_A).nombre("Teclado")
                .precio(new PrecioProducto(20.0, null, null)).stock(10).visible(true).build();
        Producto raton = Producto.builder().id(ID_RATON).idVendedor(VENDEDOR_B).nombre("Raton")
                .precio(new PrecioProducto(5.5, null, null)).stock(10).visible(true).build();
        when(repositorioProducto.findAllById(any())).thenReturn(List.of(teclado, raton));
    }

    @Test
    void cadaLineaLlevaSuVendedorConSuNombreComercialOSuNombre() {
        // Given: A tiene nombre comercial; B no, se usa su nombre
        Usuario vendedorA = vendedor(VENDEDOR_A, PerfilUsuario.builder().nombre("Pepe").nombreComercial("TiendaA").build());
        Usuario vendedorB = vendedor(VENDEDOR_B, PerfilUsuario.builder().nombre("Laura").build());
        when(repositorioUsuario.findAllById(any())).thenReturn(List.of(vendedorA, vendedorB));
        // When
        CarritoDTO carrito = servicio.obtener(ID_CLIENTE, false);
        // Then
        assertThat(carrito.lineas().get(0).idVendedor()).isEqualTo(VENDEDOR_A);
        assertThat(carrito.lineas().get(0).nombreVendedor()).isEqualTo("TiendaA");
        assertThat(carrito.lineas().get(1).idVendedor()).isEqualTo(VENDEDOR_B);
        assertThat(carrito.lineas().get(1).nombreVendedor()).isEqualTo("Laura");
    }

    @Test
    void unVendedorQueYaNoExisteSaleConUnNombreGenerico() {
        // Given
        when(repositorioUsuario.findAllById(any())).thenReturn(List.of());
        // When
        CarritoDTO carrito = servicio.obtener(ID_CLIENTE, false);
        // Then
        assertThat(carrito.lineas().get(0).nombreVendedor()).isEqualTo(ServicioCarrito.VENDEDOR_SIN_NOMBRE);
    }

    private static Usuario vendedor(String id, PerfilUsuario perfil) {
        Usuario usuario = mock(Usuario.class);
        when(usuario.getId()).thenReturn(id);
        when(usuario.getPerfil()).thenReturn(perfil);
        return usuario;
    }
}