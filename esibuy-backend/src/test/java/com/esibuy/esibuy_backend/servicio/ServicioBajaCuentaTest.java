package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import com.esibuy.esibuy_backend.excepcion.ConflictoNegocioException;
import com.esibuy.esibuy_backend.excepcion.OperacionNoPermitidaException;
import com.esibuy.esibuy_backend.excepcion.RecursoNoEncontradoException;
import com.esibuy.esibuy_backend.modelo.Direccion;
import com.esibuy.esibuy_backend.modelo.Favorito;
import com.esibuy.esibuy_backend.modelo.ListaProductos;
import com.esibuy.esibuy_backend.modelo.MetodoPago;
import com.esibuy.esibuy_backend.modelo.Pago;
import com.esibuy.esibuy_backend.modelo.PagoSuscripcion;
import com.esibuy.esibuy_backend.modelo.Pedido;
import com.esibuy.esibuy_backend.modelo.PerfilUsuario;
import com.esibuy.esibuy_backend.modelo.Producto;
import com.esibuy.esibuy_backend.modelo.Rol;
import com.esibuy.esibuy_backend.modelo.TokenRecuperacion;
import com.esibuy.esibuy_backend.modelo.Usuario;
import com.esibuy.esibuy_backend.modelo.Valoracion;
import com.esibuy.esibuy_backend.repositorio.RepositorioUsuario;

@ExtendWith(MockitoExtension.class)
class ServicioBajaCuentaTest {

    private static final String ID = "665f1c2e9b1d4a0012345678";
    private static final ObjectId ID_FICTICIO = new ObjectId(Pedido.ID_CLIENTE_FICTICIO);

    @Mock
    private RepositorioUsuario repositorioUsuario;
    @Mock
    private ServicioCarrito servicioCarrito;
    @Mock
    private MongoTemplate mongoTemplate;
    private ServicioBajaCuenta servicio;

    @BeforeEach
    void crearServicio() {
        servicio = new ServicioBajaCuenta(repositorioUsuario, servicioCarrito, mongoTemplate);
    }

    private void existeUsuarioCon(Rol rol) {
        PerfilUsuario perfil = PerfilUsuario.builder().nombre("Ana").apellidos("Lopez").build();
        when(repositorioUsuario.findById(ID)).thenReturn(Optional.of(new Usuario("ana@gmail.com", "hash", rol, perfil)));
    }

    @Test
    void cliente_borraTodoSalvoLosPedidosYLoUltimoElUsuario() {
        existeUsuarioCon(Rol.PREMIUM);

        servicio.darDeBaja(ID);

        verify(servicioCarrito).liberarYBorrarCarrito(ID);
        for (Class<?> coleccion : List.of(Direccion.class, ListaProductos.class, Favorito.class, MetodoPago.class,
                TokenRecuperacion.class)) {
            verify(mongoTemplate).remove(any(Query.class), eq(coleccion));
        }
        for (Class<?> conservada : List.of(Pedido.class, Valoracion.class, Pago.class, PagoSuscripcion.class)) {
            verify(mongoTemplate, never()).remove(any(Query.class), eq(conservada));
        }
        verify(repositorioUsuario).deleteById(ID);
    }

    @Test
    void cliente_pasaPedidosValoracionesYPagosAlFicticioYBorraLaDireccionDeLosPedidos() {
        existeUsuarioCon(Rol.CLIENTE);

        servicio.darDeBaja(ID);

        for (Class<?> conservada : List.of(Valoracion.class, Pago.class, PagoSuscripcion.class)) {
            ArgumentCaptor<Update> cambio = ArgumentCaptor.forClass(Update.class);
            verify(mongoTemplate).updateMulti(any(Query.class), cambio.capture(), eq(conservada));
            assertThat(cambio.getValue().getUpdateObject().get("$set", Document.class).get("idCliente"))
                    .isEqualTo(ID_FICTICIO);
        }
        ArgumentCaptor<Query> consulta = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> cambio = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateMulti(consulta.capture(), cambio.capture(), eq(Pedido.class));
        assertThat(consulta.getValue().getQueryObject().get("idCliente")).isEqualTo(new ObjectId(ID));
        Document cambios = cambio.getValue().getUpdateObject().get("$set", Document.class);
        assertThat(cambios.get("idCliente")).isEqualTo(ID_FICTICIO);
        assertThat(cambios.get("direccionEnvio", Document.class)).isEmpty();
    }

    @Test
    void clienteConPedidosEnCurso_devuelveConflictoYNoBorraNada() {
        existeUsuarioCon(Rol.CLIENTE);
        when(mongoTemplate.exists(any(Query.class), eq(Pedido.class))).thenReturn(true);

        assertThatThrownBy(() -> servicio.darDeBaja(ID))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasMessage(ServicioBajaCuenta.MENSAJE_CLIENTE_CON_PEDIDOS);

        ArgumentCaptor<Query> consulta = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).exists(consulta.capture(), eq(Pedido.class));
        // Un pedido entregado que el cliente aun no ha confirmado tambien le impide darse de baja
        assertThat(consulta.getValue().getQueryObject().get("estado", Document.class).getList("$in", String.class))
                .contains("PAGADO", "ENTREGADO").doesNotContain("FINALIZADO", "CANCELADO");
        verify(servicioCarrito, never()).liberarYBorrarCarrito(anyString());
        verify(repositorioUsuario, never()).deleteById(anyString());
    }

    @Test
    void vendedor_losPedidosEntregadosNoLeImpidenDarseDeBaja() {
        existeUsuarioCon(Rol.VENDEDOR);

        servicio.darDeBaja(ID);

        ArgumentCaptor<Query> consulta = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).exists(consulta.capture(), eq(Pedido.class));
        assertThat(consulta.getValue().getQueryObject().get("estado", Document.class).getList("$in", String.class))
                .contains("PAGADO", "EN_REPARTO").doesNotContain("ENTREGADO", "FINALIZADO", "CANCELADO");
        verify(repositorioUsuario).deleteById(ID);
    }

    @Test
    void vendedorSinPedidosPendientes_borraSusProductosYConservaLosPedidos() {
        existeUsuarioCon(Rol.VENDEDOR);
        when(mongoTemplate.exists(any(Query.class), eq(Pedido.class))).thenReturn(false);

        servicio.darDeBaja(ID);

        verify(mongoTemplate).remove(any(Query.class), eq(Producto.class));
        verify(mongoTemplate, never()).remove(any(Query.class), eq(Pedido.class));
        verify(mongoTemplate, never()).updateMulti(any(Query.class), any(Update.class), eq(Pedido.class));
        verify(repositorioUsuario).deleteById(ID);
    }

    @Test
    void vendedorConPedidosPendientes_devuelveConflictoYNoBorraNada() {
        existeUsuarioCon(Rol.VENDEDOR);
        when(mongoTemplate.exists(any(Query.class), eq(Pedido.class))).thenReturn(true);

        assertThatThrownBy(() -> servicio.darDeBaja(ID))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasMessage(ServicioBajaCuenta.MENSAJE_PEDIDOS_EN_CURSO);

        verify(mongoTemplate, never()).remove(any(Query.class), any(Class.class));
        verify(repositorioUsuario, never()).deleteById(anyString());
    }

    @Test
    void administrador_noPuedeDarseDeBaja() {
        existeUsuarioCon(Rol.ADMIN);

        assertThatThrownBy(() -> servicio.darDeBaja(ID)).isInstanceOf(OperacionNoPermitidaException.class);

        verify(mongoTemplate, never()).remove(any(Query.class), any(Class.class));
        verify(repositorioUsuario, never()).deleteById(anyString());
    }

    @Test
    void usuarioInexistente_lanzaNoEncontrado() {
        when(repositorioUsuario.findById(ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.darDeBaja(ID)).isInstanceOf(RecursoNoEncontradoException.class);
    }
}
