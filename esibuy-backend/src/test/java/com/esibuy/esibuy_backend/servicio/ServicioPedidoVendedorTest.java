package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.util.ReflectionTestUtils;

import com.esibuy.esibuy_backend.dto.LineaPedidoRecibidoDTO;
import com.esibuy.esibuy_backend.dto.PedidoRecibidoDTO;
import com.esibuy.esibuy_backend.dto.PedidoRecibidoDetalleDTO;
import com.esibuy.esibuy_backend.excepcion.EstadoPedidoNoPermitidoException;
import com.esibuy.esibuy_backend.excepcion.PedidoInvalidoException;
import com.esibuy.esibuy_backend.excepcion.PedidoNoEncontradoException;
import com.esibuy.esibuy_backend.modelo.DireccionEnvio;
import com.esibuy.esibuy_backend.modelo.EstadoPedido;
import com.esibuy.esibuy_backend.modelo.EventoSeguimiento;
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
 *
 * Procesar estado del pedido: el vendedor solo hace avanzar el envio de sus pedidos, nunca hacia atras ni en uno ya
 * entregado, finalizado o cancelado, y cada cambio guarda su fecha. El cambio no pisa uno hecho a la vez por el
 * cliente.
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
    private static final Instant AHORA = Instant.parse("2026-11-03T12:00:00Z");

    @Mock
    private RepositorioPedido repositorioPedido;
    @Mock
    private RepositorioUsuario repositorioUsuario;
    @Mock
    private MongoTemplate mongoTemplate;
    @Spy
    private Clock reloj = Clock.fixed(AHORA, ZoneOffset.UTC);
    @Spy
    private CalculadoraEntrega calculadoraEntrega = new CalculadoraEntrega(5);
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
        // Recien hecho: su ultima actualizacion es la fecha del pedido y el vendedor puede empezar el envio
        assertThat(detalle.fechaActualizacion()).isEqualTo(FECHA);
        // Sin fecha indicada por el vendedor: la del pedido (2 de noviembre) mas 5 dias
        assertThat(detalle.fechaEntregaEstimada()).isEqualTo(LocalDate.of(2026, 11, 7));
        assertThat(detalle.estadosSiguientes()).containsExactly(EstadoPedido.EN_PREPARACION, EstadoPedido.ENVIADO,
                EstadoPedido.EN_REPARTO, EstadoPedido.ENTREGADO);
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

    // ---------- Cambiar estado ----------

    @Test
    void cambiarEstado_alSiguientePaso_loGuardaConLaFechaYDevuelveElDetalleActualizado() {
        // Given
        when(repositorioPedido.findById(ID_PEDIDO))
                .thenReturn(Optional.of(pedido(ID_PEDIDO, ID_VENDEDOR, ID_CLIENTE, FECHA, EstadoPedido.PAGADO)));
        Pedido actualizado = pedido(ID_PEDIDO, ID_VENDEDOR, ID_CLIENTE, FECHA, EstadoPedido.EN_PREPARACION);
        ReflectionTestUtils.setField(actualizado, "fechaActualizacion", AHORA);
        ArgumentCaptor<Query> consulta = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> cambio = ArgumentCaptor.forClass(Update.class);
        when(mongoTemplate.findAndModify(consulta.capture(), cambio.capture(), any(FindAndModifyOptions.class),
                eq(Pedido.class))).thenReturn(actualizado);
        when(repositorioUsuario.findAllById(any())).thenReturn(List.of(cliente(ID_CLIENTE, "Ana", "López")));
        // When
        PedidoRecibidoDetalleDTO detalle = servicio.cambiarEstado(ID_VENDEDOR, ID_PEDIDO, EstadoPedido.EN_PREPARACION,
                null);
        // Then: solo se cambia si el pedido sigue en el estado comprobado
        Document filtro = consulta.getValue().getQueryObject();
        assertThat(filtro.get("_id")).isEqualTo(new ObjectId(ID_PEDIDO));
        assertThat(filtro.get("estado")).isEqualTo("PAGADO");
        Document nuevosValores = cambio.getValue().getUpdateObject().get("$set", Document.class);
        assertThat(nuevosValores.get("estado")).isEqualTo("EN_PREPARACION");
        assertThat(nuevosValores.get("fechaActualizacion")).isEqualTo(AHORA);
        assertThat(nuevosValores).doesNotContainKey("fechaEntregaEstimada");
        // y queda anotado en el seguimiento del pedido (Estado del envio)
        Object anotado = cambio.getValue().getUpdateObject().get("$push", Document.class).get("seguimiento");
        assertThat(anotado).isInstanceOfSatisfying(EventoSeguimiento.class, evento -> {
            assertThat(evento.getEstado()).isEqualTo(EstadoPedido.EN_PREPARACION);
            assertThat(evento.getFecha()).isEqualTo(AHORA);
        });
        // y devuelve el pedido ya cambiado, con los pasos que le quedan
        assertThat(detalle.estado()).isEqualTo(EstadoPedido.EN_PREPARACION);
        assertThat(detalle.fechaActualizacion()).isEqualTo(AHORA);
        assertThat(detalle.estadosSiguientes()).containsExactly(EstadoPedido.ENVIADO, EstadoPedido.EN_REPARTO,
                EstadoPedido.ENTREGADO);
        assertThat(detalle.cliente()).isEqualTo("Ana López");
    }

    @Test
    void cambiarEstado_puedeSaltarsePasosHaciaDelante() {
        // Given: de pagado directamente a enviado
        when(repositorioPedido.findById(ID_PEDIDO))
                .thenReturn(Optional.of(pedido(ID_PEDIDO, ID_VENDEDOR, ID_CLIENTE, FECHA, EstadoPedido.PAGADO)));
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
                eq(Pedido.class))).thenReturn(pedido(ID_PEDIDO, ID_VENDEDOR, ID_CLIENTE, FECHA, EstadoPedido.ENVIADO));
        when(repositorioUsuario.findAllById(any())).thenReturn(List.of(cliente(ID_CLIENTE, "Ana", "López")));
        // When
        PedidoRecibidoDetalleDTO detalle = servicio.cambiarEstado(ID_VENDEDOR, ID_PEDIDO, EstadoPedido.ENVIADO, null);
        // Then
        assertThat(detalle.estado()).isEqualTo(EstadoPedido.ENVIADO);
    }

    @ParameterizedTest
    @CsvSource({
        "ENVIADO, EN_PREPARACION",
        "ENVIADO, ENVIADO",
        "EN_REPARTO, PAGADO",
        "PAGADO, PENDIENTE",
        "PAGADO, FINALIZADO",
        "PAGADO, CANCELADO"
    })
    void cambiarEstado_haciaAtrasAlMismoOFueraDelEnvio_lanzaConflictoSinGuardar(EstadoPedido actual,
                                                                              EstadoPedido nuevo) {
        // Given
        when(repositorioPedido.findById(ID_PEDIDO))
                .thenReturn(Optional.of(pedido(ID_PEDIDO, ID_VENDEDOR, ID_CLIENTE, FECHA, actual)));
        // When / Then
        assertThatThrownBy(() -> servicio.cambiarEstado(ID_VENDEDOR, ID_PEDIDO, nuevo, null))
                .isInstanceOf(EstadoPedidoNoPermitidoException.class)
                .hasMessage(ServicioPedidoVendedor.MENSAJE_SOLO_AVANZA);
        verify(mongoTemplate, never()).findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(Pedido.class));
    }

    @ParameterizedTest
    @EnumSource(value = EstadoPedido.class, names = {"ENTREGADO", "FINALIZADO", "CANCELADO"})
    void cambiarEstado_deUnPedidoEntregadoFinalizadoOCancelado_lanzaConflictoSinGuardar(EstadoPedido actual) {
        // Given
        when(repositorioPedido.findById(ID_PEDIDO))
                .thenReturn(Optional.of(pedido(ID_PEDIDO, ID_VENDEDOR, ID_CLIENTE, FECHA, actual)));
        // When / Then
        assertThatThrownBy(() -> servicio.cambiarEstado(ID_VENDEDOR, ID_PEDIDO, EstadoPedido.ENTREGADO, null))
                .isInstanceOf(EstadoPedidoNoPermitidoException.class)
                .hasMessage(ServicioPedidoVendedor.MENSAJE_SIN_MAS_CAMBIOS);
        verify(mongoTemplate, never()).findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(Pedido.class));
    }

    @Test
    void cambiarEstado_siElPedidoCambioALaVez_lanzaConflictoYNoPisaElOtroCambio() {
        // Given: al guardar, el pedido ya no esta PAGADO (el cliente lo acaba de cancelar)
        when(repositorioPedido.findById(ID_PEDIDO))
                .thenReturn(Optional.of(pedido(ID_PEDIDO, ID_VENDEDOR, ID_CLIENTE, FECHA, EstadoPedido.PAGADO)));
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
                eq(Pedido.class))).thenReturn(null);
        // When / Then
        assertThatThrownBy(() -> servicio.cambiarEstado(ID_VENDEDOR, ID_PEDIDO, EstadoPedido.EN_PREPARACION,
                null))
                .isInstanceOf(EstadoPedidoNoPermitidoException.class)
                .hasMessage(ServicioPedidoVendedor.MENSAJE_CAMBIADO_A_LA_VEZ);
    }

    @Test
    void cambiarEstado_deUnPedidoDeOtroVendedor_lanzaNoEncontradoSinGuardar() {
        // Given
        when(repositorioPedido.findById(ID_PEDIDO))
                .thenReturn(Optional.of(pedido(ID_PEDIDO, ID_OTRO_VENDEDOR, ID_CLIENTE, FECHA, EstadoPedido.PAGADO)));
        // When / Then
        assertThatThrownBy(() -> servicio.cambiarEstado(ID_VENDEDOR, ID_PEDIDO, EstadoPedido.ENVIADO, null))
                .isInstanceOf(PedidoNoEncontradoException.class);
        verify(mongoTemplate, never()).findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(Pedido.class));
    }

    @Test
    void cambiarEstado_sinIndicarElEstado_lanzaPeticionInvalidaSinConsultar() {
        // When / Then
        assertThatThrownBy(() -> servicio.cambiarEstado(ID_VENDEDOR, ID_PEDIDO, null, null))
                .isInstanceOf(PedidoInvalidoException.class)
                .hasMessage(ServicioPedidoVendedor.MENSAJE_ESTADO_OBLIGATORIO);
        verify(repositorioPedido, never()).findById(anyString());
    }

    // ---------- Fecha estimada de entrega (Estado del envio) ----------

    @Test
    void cambiarEstado_aEnviadoConFechaEstimada_guardaEsaFecha() {
        // Given
        when(repositorioPedido.findById(ID_PEDIDO))
                .thenReturn(Optional.of(
                        pedido(ID_PEDIDO, ID_VENDEDOR, ID_CLIENTE, FECHA, EstadoPedido.EN_PREPARACION)));
        ArgumentCaptor<Update> cambio = ArgumentCaptor.forClass(Update.class);
        when(mongoTemplate.findAndModify(any(Query.class), cambio.capture(), any(FindAndModifyOptions.class),
                eq(Pedido.class))).thenReturn(pedido(ID_PEDIDO, ID_VENDEDOR, ID_CLIENTE, FECHA, EstadoPedido.ENVIADO));
        when(repositorioUsuario.findAllById(any())).thenReturn(List.of(cliente(ID_CLIENTE, "Ana", "López")));
        // When
        servicio.cambiarEstado(ID_VENDEDOR, ID_PEDIDO, EstadoPedido.ENVIADO, LocalDate.of(2026, 11, 10));
        // Then: se guarda como el inicio de ese dia en la hora de la tienda
        Document nuevosValores = cambio.getValue().getUpdateObject().get("$set", Document.class);
        assertThat(nuevosValores.get("fechaEntregaEstimada"))
                .isEqualTo(CalculadoraEntrega.aInstante(LocalDate.of(2026, 11, 10)));
    }

    @Test
    void cambiarEstado_conFechaEstimadaDeHoy_seAcepta() {
        // Given: hoy es 3 de noviembre
        when(repositorioPedido.findById(ID_PEDIDO))
                .thenReturn(Optional.of(pedido(ID_PEDIDO, ID_VENDEDOR, ID_CLIENTE, FECHA, EstadoPedido.ENVIADO)));
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
                eq(Pedido.class)))
                .thenReturn(pedido(ID_PEDIDO, ID_VENDEDOR, ID_CLIENTE, FECHA, EstadoPedido.EN_REPARTO));
        when(repositorioUsuario.findAllById(any())).thenReturn(List.of(cliente(ID_CLIENTE, "Ana", "López")));
        // When
        PedidoRecibidoDetalleDTO detalle = servicio.cambiarEstado(ID_VENDEDOR, ID_PEDIDO, EstadoPedido.EN_REPARTO,
                LocalDate.of(2026, 11, 3));
        // Then
        assertThat(detalle.estado()).isEqualTo(EstadoPedido.EN_REPARTO);
    }

    @Test
    void cambiarEstado_conFechaEstimadaPasada_lanzaPeticionInvalidaSinConsultar() {
        // When / Then
        assertThatThrownBy(() -> servicio.cambiarEstado(ID_VENDEDOR, ID_PEDIDO, EstadoPedido.ENVIADO,
                LocalDate.of(2026, 11, 2)))
                .isInstanceOf(PedidoInvalidoException.class)
                .hasMessage(ServicioPedidoVendedor.MENSAJE_FECHA_PASADA);
        verify(repositorioPedido, never()).findById(anyString());
    }

    @ParameterizedTest
    @EnumSource(value = EstadoPedido.class, names = {"EN_PREPARACION", "ENTREGADO"})
    void cambiarEstado_conFechaEstimadaSinEstarEnviandolo_lanzaPeticionInvalida(EstadoPedido nuevo) {
        // When / Then
        assertThatThrownBy(() -> servicio.cambiarEstado(ID_VENDEDOR, ID_PEDIDO, nuevo, LocalDate.of(2026, 11, 10)))
                .isInstanceOf(PedidoInvalidoException.class)
                .hasMessage(ServicioPedidoVendedor.MENSAJE_FECHA_SOLO_AL_ENVIAR);
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
