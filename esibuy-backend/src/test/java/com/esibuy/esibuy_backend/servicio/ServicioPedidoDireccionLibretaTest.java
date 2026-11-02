package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;

import com.esibuy.esibuy_backend.dto.DireccionEnvioDTO;
import com.esibuy.esibuy_backend.dto.PedidoDTO;
import com.esibuy.esibuy_backend.excepcion.RecursoNoEncontradoException;
import com.esibuy.esibuy_backend.modelo.Carrito;
import com.esibuy.esibuy_backend.modelo.DireccionEnvio;
import com.esibuy.esibuy_backend.modelo.Pedido;
import com.esibuy.esibuy_backend.modelo.PrecioProducto;
import com.esibuy.esibuy_backend.modelo.Producto;
import com.esibuy.esibuy_backend.repositorio.RepositorioCarrito;
import com.esibuy.esibuy_backend.repositorio.RepositorioPedido;
import com.esibuy.esibuy_backend.repositorio.RepositorioProducto;
import com.esibuy.esibuy_backend.repositorio.RepositorioValoracion;

@ExtendWith(MockitoExtension.class)
class ServicioPedidoDireccionLibretaTest {

    private static final String CLIENTE = "665f1c2e9b1d4a0012345678";
    private static final String DIRECCION = "665f1c2e9b1d4a0087654321";
    private static final String PRODUCTO = "665f1c2e9b1d4a00aaaaaaaa";
    private static final String VENDEDOR = "665f1c2e9b1d4a00bbbbbbbb";

    @Mock
    private RepositorioPedido repositorioPedido;
    @Mock
    private RepositorioCarrito repositorioCarrito;
    @Mock
    private RepositorioProducto repositorioProducto;
    @Mock
    private RepositorioValoracion repositorioValoracion;
    @Mock
    private MongoTemplate mongoTemplate;
    @Mock
    private ServicioDireccion servicioDireccion;
    private ServicioPedido servicio;

    @BeforeEach
    void crearServicio() {
        Clock hoy = Clock.fixed(Instant.parse("2026-11-02T10:00:00Z"), ZoneId.of("Europe/Madrid"));
        servicio = new ServicioPedido(repositorioPedido, repositorioCarrito, repositorioProducto,
                repositorioValoracion, mongoTemplate, hoy, servicioDireccion);
    }

    @Test
    void realizar_conIdDireccion_guardaLaCopiaDeLaLibretaYNoElTextoRecibido() {
        when(servicioDireccion.paraPedido(CLIENTE, DIRECCION))
                .thenReturn(new DireccionEnvio("Calle Mayor 5, 2B", "Ciudad Real", "13001", DIRECCION));
        Carrito carrito = new Carrito(CLIENTE);
        carrito.anadir(PRODUCTO, 1);
        when(repositorioCarrito.buscarPorCliente(new ObjectId(CLIENTE))).thenReturn(Optional.of(carrito));
        when(repositorioProducto.findAllById(List.of(PRODUCTO))).thenReturn(List.of(Producto.builder()
                .id(PRODUCTO).idVendedor(VENDEDOR).nombre("Libro").visible(true).stock(5)
                .precio(new PrecioProducto(10.0, null, null)).build()));
        when(repositorioPedido.save(any(Pedido.class))).thenAnswer(inv -> inv.getArgument(0));

        // El texto que llega se ignora: manda la direccion guardada
        PedidoDTO pedido = servicio.realizar(CLIENTE, false,
                new DireccionEnvioDTO("Otra calle", "Otra ciudad", "99999", DIRECCION), null);

        ArgumentCaptor<Pedido> guardado = ArgumentCaptor.forClass(Pedido.class);
        verify(repositorioPedido).save(guardado.capture());
        assertThat(guardado.getValue().getDireccionEnvio().getIdDireccion()).isEqualTo(DIRECCION);
        assertThat(pedido.direccionEnvio())
                .isEqualTo(new DireccionEnvioDTO("Calle Mayor 5, 2B", "Ciudad Real", "13001", DIRECCION));
    }

    @Test
    void realizar_conDireccionDeOtroCliente_lanza404SinCrearPedidoNiTocarElCarrito() {
        when(servicioDireccion.paraPedido(CLIENTE, DIRECCION))
                .thenThrow(new RecursoNoEncontradoException("La direccion no existe"));

        assertThatThrownBy(() -> servicio.realizar(CLIENTE, false,
                new DireccionEnvioDTO(null, null, null, DIRECCION), null))
                .isInstanceOf(RecursoNoEncontradoException.class);
        verifyNoInteractions(repositorioPedido, repositorioCarrito);
    }
}