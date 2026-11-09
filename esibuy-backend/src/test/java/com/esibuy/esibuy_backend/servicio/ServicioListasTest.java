package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.util.ReflectionTestUtils;

import com.esibuy.esibuy_backend.excepcion.ConflictoNegocioException;
import com.esibuy.esibuy_backend.excepcion.RecursoNoEncontradoException;
import com.esibuy.esibuy_backend.modelo.ListaProductos;
import com.esibuy.esibuy_backend.modelo.Producto;
import com.esibuy.esibuy_backend.repositorio.RepositorioFavorito;
import com.esibuy.esibuy_backend.repositorio.RepositorioListaProductos;

@ExtendWith(MockitoExtension.class)
class ServicioListasTest {

    private static final String USUARIO = "665f1c2e9b1d4a0012345678";
    private static final String LISTA = "665f1c2e9b1d4a0087654321";
    private static final String PRODUCTO = "665f1c2e9b1d4a00bbbbbbbb";
    private static final ObjectId ID_USUARIO = new ObjectId(USUARIO);

    @Mock
    private RepositorioListaProductos repositorio;
    // Sin favoritos antiguos que migrar (un mock devuelve una lista vacia)
    @Mock
    private RepositorioFavorito repositorioFavorito;
    @Mock
    private MongoTemplate mongoTemplate;
    @InjectMocks
    private ServicioListas servicio;

    private void existeLista(ListaProductos lista) {
        ReflectionTestUtils.setField(lista, "id", LISTA);
        when(repositorio.buscarPorIdYUsuario(new ObjectId(LISTA), ID_USUARIO)).thenReturn(Optional.of(lista));
    }

    @Test
    void listar_clienteSinFavoritos_laCrea() {
        when(repositorio.existeFavoritos(ID_USUARIO)).thenReturn(false);
        when(repositorio.buscarPorUsuario(ID_USUARIO)).thenReturn(List.of());

        servicio.listar(USUARIO);

        ArgumentCaptor<ListaProductos> guardada = ArgumentCaptor.forClass(ListaProductos.class);
        verify(repositorio).save(guardada.capture());
        assertThat(guardada.getValue().isPorDefecto()).isTrue();
        assertThat(guardada.getValue().getNombre()).isEqualTo(ListaProductos.NOMBRE_FAVORITOS);
    }

    @Test
    void listar_clienteConFavoritos_noCreaOtra() {
        when(repositorio.existeFavoritos(ID_USUARIO)).thenReturn(true);
        when(repositorio.buscarPorUsuario(ID_USUARIO)).thenReturn(List.of());

        servicio.listar(USUARIO);

        verify(repositorio, never()).save(any());
    }

    @Test
    void listar_favoritosCreadaALaVezPorOtraPeticion_noFalla() {
        when(repositorio.existeFavoritos(ID_USUARIO)).thenReturn(false);
        when(repositorio.save(any(ListaProductos.class))).thenThrow(new DuplicateKeyException("duplicada"));
        when(repositorio.buscarPorUsuario(ID_USUARIO)).thenReturn(List.of());

        assertThatCode(() -> servicio.listar(USUARIO)).doesNotThrowAnyException();
    }

    @Test
    void crear_nombreRepetido_lanzaConflictoYNoGuarda() {
        when(repositorio.existeFavoritos(ID_USUARIO)).thenReturn(true);
        when(repositorio.contarPorUsuario(ID_USUARIO)).thenReturn(2L);
        when(repositorio.existePorUsuarioYNombre(ID_USUARIO, "Moda Verano")).thenReturn(true);

        assertThatThrownBy(() -> servicio.crear(USUARIO, "  Moda   Verano "))
                .isInstanceOf(ConflictoNegocioException.class)
                .extracting("codigo").isEqualTo(ServicioListas.CODIGO_NOMBRE_DUPLICADO);
        verify(repositorio, never()).save(any());
    }

    @Test
    void crear_limiteDeListasAlcanzado_lanzaConflicto() {
        when(repositorio.existeFavoritos(ID_USUARIO)).thenReturn(true);
        when(repositorio.contarPorUsuario(ID_USUARIO)).thenReturn((long) ServicioListas.MAXIMO_LISTAS);

        assertThatThrownBy(() -> servicio.crear(USUARIO, "Deporte"))
                .isInstanceOf(ConflictoNegocioException.class)
                .extracting("codigo").isEqualTo(ServicioListas.CODIGO_LIMITE_LISTAS);
    }

    @Test
    void renombrar_favoritos_lanzaConflicto() {
        existeLista(ListaProductos.favoritos(USUARIO));

        assertThatThrownBy(() -> servicio.renombrar(USUARIO, LISTA, "Otra"))
                .isInstanceOf(ConflictoNegocioException.class)
                .extracting("codigo").isEqualTo(ServicioListas.CODIGO_LISTA_POR_DEFECTO);
    }

    @Test
    void eliminar_favoritos_lanzaConflictoYNoBorra() {
        existeLista(ListaProductos.favoritos(USUARIO));

        assertThatThrownBy(() -> servicio.eliminar(USUARIO, LISTA)).isInstanceOf(ConflictoNegocioException.class);
        verify(repositorio, never()).delete(any());
    }

    @Test
    void anadirProducto_listaDeOtroUsuario_lanzaNoEncontradaSinMirarElProducto() {
        when(repositorio.buscarPorIdYUsuario(new ObjectId(LISTA), ID_USUARIO)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.anadirProducto(USUARIO, LISTA, PRODUCTO, false))
                .isInstanceOf(RecursoNoEncontradoException.class);
        verifyNoInteractions(mongoTemplate);
    }

    @Test
    void anadirProducto_noDisponible_lanzaNoEncontradoYNoGuarda() {
        existeLista(ListaProductos.nueva(USUARIO, "Moda"));
        when(mongoTemplate.exists(any(Query.class), eq(Producto.class))).thenReturn(false);

        assertThatThrownBy(() -> servicio.anadirProducto(USUARIO, LISTA, PRODUCTO, false))
                .isInstanceOf(RecursoNoEncontradoException.class);
        verify(repositorio, never()).save(any());
    }

    @Test
    void anadirProducto_clienteEstandar_excluyeLosProductosSoloPremium() {
        existeLista(ListaProductos.nueva(USUARIO, "Moda"));
        when(mongoTemplate.exists(any(Query.class), eq(Producto.class))).thenReturn(true);
        when(repositorio.save(any(ListaProductos.class))).thenAnswer(inv -> inv.getArgument(0));

        servicio.anadirProducto(USUARIO, LISTA, PRODUCTO, false);

        ArgumentCaptor<Query> consulta = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).exists(consulta.capture(), eq(Producto.class));
        assertThat(consulta.getValue().getQueryObject()).containsKey("soloPremium");
    }

    @Test
    void anadirProducto_clientePremium_noFiltraPorSoloPremium() {
        existeLista(ListaProductos.nueva(USUARIO, "Moda"));
        when(mongoTemplate.exists(any(Query.class), eq(Producto.class))).thenReturn(true);
        when(repositorio.save(any(ListaProductos.class))).thenAnswer(inv -> inv.getArgument(0));

        servicio.anadirProducto(USUARIO, LISTA, PRODUCTO, true);

        ArgumentCaptor<Query> consulta = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).exists(consulta.capture(), eq(Producto.class));
        assertThat(consulta.getValue().getQueryObject()).doesNotContainKey("soloPremium");
    }

    @Test
    void anadirProducto_yaEstaba_noGuardaDeNuevo() {
        ListaProductos lista = ListaProductos.nueva(USUARIO, "Moda");
        lista.anadirProducto(PRODUCTO);
        existeLista(lista);
        when(mongoTemplate.exists(any(Query.class), eq(Producto.class))).thenReturn(true);

        assertThat(servicio.anadirProducto(USUARIO, LISTA, PRODUCTO, false).idProductos()).containsExactly(PRODUCTO);
        verify(repositorio, never()).save(any());
    }
}