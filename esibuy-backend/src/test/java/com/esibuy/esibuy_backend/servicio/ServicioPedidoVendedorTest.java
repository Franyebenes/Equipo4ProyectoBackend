package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import com.esibuy.esibuy_backend.dto.LineaPedidoRecibidoDTO;
import com.esibuy.esibuy_backend.dto.PedidoRecibidoDTO;
import com.esibuy.esibuy_backend.dto.PedidoRecibidoDetalleDTO;
import com.esibuy.esibuy_backend.excepcion.PedidoNoEncontradoException;
import com.esibuy.esibuy_backend.modelo.DireccionEnvio;
import com.esibuy.esibuy_backend.modelo.EstadoPedido;
import com.esibuy.esibuy_backend.modelo.LineaPedido;
import com.esibuy.esibuy_backend.modelo.Pedido;
import com.esibuy.esibuy_backend.modelo.PerfilUsuario;
import com.esibuy.esibuy_backend.modelo.Rol;
import com.esibuy.esibuy_backend.modelo.Usuario;
import com.esibuy.esibuy_backend.repositorio.RepositorioPedido;
import com.esibuy.esibuy_backend.repositorio.RepositorioUsuario;

/**
 * Ver pedidos recibidos: el vendedor solo ve los pedidos de sus productos, los mas recientes primero, con el nombre del
 * cliente, el importe, el estado y, en el detalle, la direccion de envio y las lineas.
 */
@ExtendWith(MockitoExtension.class)
class ServicioPedidoVendedorTest {

    private static final String ID_VENDEDOR = "64c000000000000000000001";
    private static final String ID_OTRO_VENDEDOR = "64c000000000000000000002";
    private static final String ID_CLIENTE = "64c000000000000000000011";
    private static final String ID_CLIENTE_BORRADO = "64c000000000000000000012";
    private static final String ID_TECLADO = "64c000000000000000000021";
    private static final String ID_RATON = "64c000000000000000000022";
    private static final String ID_PEDIDO = "64c000000000000000000101";
    private static final String ID_PEDIDO_ANTIGUO = "64c000000000000000000102";
    private static final Instant FECHA = Instant.parse("2026-11-02T09:30:00Z");
    private static final Instant FECHA_ANTIGUA = Instant.parse("2026-10-20T18:00:00Z");

    @Mock
    private RepositorioPedido repositorioPedido;
    @Mock
    private RepositorioUsuario repositorioUsuario;
    @InjectMocks
    private ServicioPedidoVendedor servicio;

    // ---------- Listado ----------

    @Test
    void listar_sinPedidos_devuelveListaVaciaSinConsultarClientes() {
        // Given
        when(repositorioPedido.buscarPorVendedor(any(ObjectId.class), any(Sort.class))).thenReturn(List.of());
        // When
        List<PedidoRecibidoDTO> pedidos = servicio.listar(ID_VENDEDOR);
        // Then
        assertThat(pedidos).isEmpty();
        verify(repositorioUsuario, never()).findAllById(any());
    }

    @Test
    void listar_consultaLosPedidosDelVendedorDeLaSesionDelMasRecienteAlMasAntiguo() {
        // Given
        ArgumentCaptor<ObjectId> vendedor = ArgumentCaptor.forClass(ObjectId.class);
        ArgumentCaptor<Sort> orden = ArgumentCaptor.forClass(Sort.class);
        when(repositorioPedido.buscarPorVendedor(vendedor.capture(), orden.capture())).thenReturn(List.of());
        // When
        servicio.listar(ID_VENDEDOR);
        // Then
        assertThat(vendedor.getValue()).isEqualTo(new ObjectId(ID_VENDEDOR));
        Sort.Order porFecha = orden.getValue().getOrderFor("fecha");
        assertThat(porFecha).isNotNull();
        assertThat(porFecha.getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void listar_devuelveIdFechaClienteImporteEstadoYUnidadesRespetandoElOrden() {
        // Given: el repositorio ya los devuelve del mas reciente al mas antiguo
        when(repositorioPedido.buscarPorVendedor(any(ObjectId.class), any(Sort.class))).thenReturn(List.of(
                pedido(ID_PEDIDO, ID_VENDEDOR, ID_CLIENTE, FECHA, EstadoPedido.PAGADO),
                pedido(ID_PEDIDO_ANTIGUO, ID_VENDEDOR, ID_CLIENTE, FECHA_ANTIGUA, EstadoPedido.ENVIADO)));
        when(repositorioUsuario.findAllById(any())).thenReturn(List.of(cliente(ID_CLIENTE, "Ana", "López")));
        // When
        List<PedidoRecibidoDTO> pedidos = servicio.listar(ID_VENDEDOR);
        // Then
        assertThat(pedidos).extracting(PedidoRecibidoDTO::id).containsExactly(ID_PEDIDO, ID_PEDIDO_ANTIGUO);
        PedidoRecibidoDTO primero = pedidos.get(0);
        assertThat(primero.fecha()).isEqualTo(FECHA);
        assertThat(primero.cliente()).isEqualTo("Ana López");
        assertThat(primero.importe()).isEqualTo(45.5);
        assertThat(primero.estado()).isEqualTo(EstadoPedido.PAGADO);
        assertThat(primero.unidades()).isEqualTo(3);
        assertThat(pedidos.get(1).estado()).isEqualTo(EstadoPedido.ENVIADO);
    }

    @Test
    void listar_clienteSinNombreNiApellidos_muestraSuEmail() {
        // Given
        when(repositorioPedido.buscarPorVendedor(any(ObjectId.class), any(Sort.class)))
                .thenReturn(List.of(pedido(ID_PEDIDO, ID_VENDEDOR, ID_CLIENTE, FECHA, EstadoPedido.PAGADO)));
        when(repositorioUsuario.findAllById(any())).thenReturn(List.of(cliente(ID_CLIENTE, null, " ")));
        // When
        List<PedidoRecibidoDTO> pedidos = servicio.listar(ID_VENDEDOR);
        // Then
        assertThat(pedidos.get(0).cliente()).isEqualTo("cliente@esibuy.es");
    }

    @Test
    void listar_clienteQueYaNoExiste_loIndicaEnLugarDeSuNombre() {
        // Given: el cliente del pedido se ha borrado, pero el pedido sigue siendo del vendedor
        when(repositorioPedido.buscarPorVendedor(any(ObjectId.class), any(Sort.class)))
                .thenReturn(List.of(pedido(ID_PEDIDO, ID_VENDEDOR, ID_CLIENTE_BORRADO, FECHA, EstadoPedido.PAGADO)));
        when(repositorioUsuario.findAllById(any())).thenReturn(List.of());
        // When
        List<PedidoRecibidoDTO> pedidos = servicio.listar(ID_VENDEDOR);
        // Then
        assertThat(pedidos.get(0).cliente()).isEqualTo(ServicioPedidoVendedor.CLIENTE_NO_DISPONIBLE);
    }

    // ---------- Detalle ----------

    @Test
    void detalle_pedidoPropio_devuelveDireccionLineasYSubtotales() {
        // Given
        when(repositorioPedido.findById(ID_PEDIDO))
                .thenReturn(Optional.of(pedido(ID_PEDIDO, ID_VENDEDOR, ID_CLIENTE, FECHA, EstadoPedido.PAGADO)));
        when(repositorioUsuario.findAllById(any())).thenReturn(List.of(cliente(ID_CLIENTE, "Ana", "López")));
        // When
        PedidoRecibidoDetalleDTO detalle = servicio.detalle(ID_VENDEDOR, ID_PEDIDO);
        // Then
        assertThat(detalle.id()).isEqualTo(ID_PEDIDO);
        assertThat(detalle.cliente()).isEqualTo("Ana López");
        assertThat(detalle.importe()).isEqualTo(45.5);
        assertThat(detalle.unidades()).isEqualTo(3);
        assertThat(detalle.direccionEnvio().calle()).isEqualTo("Calle Mayor 1");
        assertThat(detalle.direccionEnvio().codigoPostal()).isEqualTo("13001");
        // El vendedor no recibe el id de la libreta de direcciones del cliente
        assertThat(detalle.direccionEnvio().idDireccion()).isNull();
        assertThat(detalle.lineas()).extracting(LineaPedidoRecibidoDTO::nombre).containsExactly("Teclado", "Ratón");
        assertThat(detalle.lineas()).extracting(LineaPedidoRecibidoDTO::subtotal).containsExactly(36.0, 9.5);
    }

    @Test
    void detalle_pedidoDeOtroVendedor_lanzaNoEncontrado() {
        // Given
        when(repositorioPedido.findById(ID_PEDIDO))
                .thenReturn(Optional.of(pedido(ID_PEDIDO, ID_OTRO_VENDEDOR, ID_CLIENTE, FECHA, EstadoPedido.PAGADO)));
        // When / Then
        assertThatThrownBy(() -> servicio.detalle(ID_VENDEDOR, ID_PEDIDO))
                .isInstanceOf(PedidoNoEncontradoException.class);
        verify(repositorioUsuario, never()).findAllById(any());
    }

    @Test
    void detalle_pedidoInexistente_lanzaNoEncontrado() {
        // Given
        when(repositorioPedido.findById(ID_PEDIDO)).thenReturn(Optional.empty());
        // When / Then
        assertThatThrownBy(() -> servicio.detalle(ID_VENDEDOR, ID_PEDIDO))
                .isInstanceOf(PedidoNoEncontradoException.class);
    }

    @Test
    void detalle_idNoValido_lanzaNoEncontradoSinConsultarLaBaseDeDatos() {
        // When / Then
        assertThatThrownBy(() -> servicio.detalle(ID_VENDEDOR, "no-es-un-id"))
                .isInstanceOf(PedidoNoEncontradoException.class);
        verify(repositorioPedido, never()).findById(anyString());
    }

    private static Pedido pedido(String id, String idVendedor, String idCliente, Instant fecha, EstadoPedido estado) {
        Pedido pedido = new Pedido(idCliente, idVendedor, new DireccionEnvio("Calle Mayor 1", "Ciudad Real", "13001"),
                List.of(new LineaPedido(ID_TECLADO, "Teclado", 2, 18.0), new LineaPedido(ID_RATON, "Ratón", 1, 9.5)),
                45.5, fecha);
        ReflectionTestUtils.setField(pedido, "id", id);
        ReflectionTestUtils.setField(pedido, "estado", estado);
        return pedido;
    }

    private static Usuario cliente(String id, String nombre, String apellidos) {
        Usuario usuario = new Usuario("cliente@esibuy.es", "$argon2id$hash", Rol.CLIENTE,
                PerfilUsuario.builder().nombre(nombre).apellidos(apellidos).build());
        ReflectionTestUtils.setField(usuario, "id", id);
        return usuario;
    }
}
