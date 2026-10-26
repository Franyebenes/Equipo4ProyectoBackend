package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.esibuy.esibuy_backend.dto.ProductoClienteDTO;
import com.esibuy.esibuy_backend.excepcion.FavoritoNoEncontradoException;
import com.esibuy.esibuy_backend.excepcion.FavoritoRepetidoException;
import com.esibuy.esibuy_backend.excepcion.ProductoNoDisponibleException;
import com.esibuy.esibuy_backend.modelo.Favorito;
import com.esibuy.esibuy_backend.modelo.Producto;
import com.esibuy.esibuy_backend.repositorio.RepositorioFavorito;
import com.esibuy.esibuy_backend.repositorio.RepositorioProducto;

/**
 * HU-31.1 Ver, HU-31.2 Anadir y HU-31.3 Eliminar favoritos: siempre los del cliente de la sesion, sin repetir y solo
 * productos que el cliente puede ver.
 */
@ExtendWith(MockitoExtension.class)
class ServicioFavoritosTest {

    private static final String ID_CLIENTE = "64b7f0c2a1b2c3d4e5f60001";
    private static final String ID_TAZA = "64b7f0c2a1b2c3d4e5f60101";
    private static final String ID_VASO = "64b7f0c2a1b2c3d4e5f60102";
    private static final Instant AHORA = Instant.parse("2026-10-26T12:00:00Z");

    @Mock
    private RepositorioFavorito repositorioFavorito;
    @Mock
    private RepositorioProducto repositorioProducto;
    @Mock
    private ServicioCatalogo servicioCatalogo;

    private ServicioFavoritos servicio;

    @BeforeEach
    void crearServicio() {
        servicio = new ServicioFavoritos(repositorioFavorito, repositorioProducto, servicioCatalogo,
                Clock.fixed(AHORA, ZoneOffset.UTC));
    }

    private void productoEnBaseDeDatos(String id, boolean activo) {
        when(repositorioProducto.findById(id))
                .thenReturn(Optional.of(Producto.builder().id(id).nombre("Taza").visible(activo).build()));
    }

    // ---------- HU-31.1 Ver ----------

    @Test
    void listar_devuelveSusFavoritosLosUltimosAnadidosPrimero() {
        // Given: añadió la taza antes que el vaso
        when(repositorioFavorito.buscarPorCliente(new ObjectId(ID_CLIENTE))).thenReturn(List.of(
                new Favorito(ID_CLIENTE, ID_TAZA, AHORA.minusSeconds(60)), new Favorito(ID_CLIENTE, ID_VASO, AHORA)));
        List<ProductoClienteDTO> productos = List.of();
        when(servicioCatalogo.productosActivos(List.of(ID_VASO, ID_TAZA), false)).thenReturn(productos);

        // When / Then: con la informacion del catalogo, en ese orden
        assertThat(servicio.listar(ID_CLIENTE, false)).isSameAs(productos);
    }

    @Test
    void listar_sinFavoritos_pideUnaListaVacia() {
        when(repositorioFavorito.buscarPorCliente(new ObjectId(ID_CLIENTE))).thenReturn(List.of());
        when(servicioCatalogo.productosActivos(List.of(), true)).thenReturn(List.of());

        assertThat(servicio.listar(ID_CLIENTE, true)).isEmpty();
    }

    @Test
    void idsFavoritos_devuelveLosIdsDeSusProductosFavoritos() {
        when(repositorioFavorito.buscarPorCliente(new ObjectId(ID_CLIENTE)))
                .thenReturn(List.of(new Favorito(ID_CLIENTE, ID_TAZA, AHORA)));

        assertThat(servicio.idsFavoritos(ID_CLIENTE)).containsExactly(ID_TAZA);
    }

    // ---------- HU-31.2 Anadir ----------

    @Test
    void anadir_productoActivo_loGuardaParaEseClienteConLaFecha() {
        // Given
        productoEnBaseDeDatos(ID_TAZA, true);
        when(repositorioFavorito.existe(new ObjectId(ID_CLIENTE), new ObjectId(ID_TAZA))).thenReturn(false);

        // When
        servicio.anadir(ID_CLIENTE, ID_TAZA);

        // Then
        ArgumentCaptor<Favorito> guardado = ArgumentCaptor.forClass(Favorito.class);
        verify(repositorioFavorito).save(guardado.capture());
        assertThat(guardado.getValue().getIdCliente()).isEqualTo(ID_CLIENTE);
        assertThat(guardado.getValue().getIdProducto()).isEqualTo(ID_TAZA);
        assertThat(guardado.getValue().getAnadidoEn()).isEqualTo(AHORA);
    }

    @Test
    void anadir_productoQueYaEsFavorito_lanzaFavoritoRepetidoYNoLoRepite() {
        productoEnBaseDeDatos(ID_TAZA, true);
        when(repositorioFavorito.existe(new ObjectId(ID_CLIENTE), new ObjectId(ID_TAZA))).thenReturn(true);

        assertThatThrownBy(() -> servicio.anadir(ID_CLIENTE, ID_TAZA)).isInstanceOf(FavoritoRepetidoException.class);
        verify(repositorioFavorito, never()).save(any());
    }

    @Test
    void anadir_productoInactivo_lanzaProductoNoDisponible() {
        productoEnBaseDeDatos(ID_TAZA, false);

        assertThatThrownBy(() -> servicio.anadir(ID_CLIENTE, ID_TAZA))
                .isInstanceOf(ProductoNoDisponibleException.class);
        verify(repositorioFavorito, never()).save(any());
    }

    @Test
    void anadir_productoQueNoExisteOIdMalFormado_lanzaProductoNoDisponible() {
        when(repositorioProducto.findById(ID_TAZA)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.anadir(ID_CLIENTE, ID_TAZA))
                .isInstanceOf(ProductoNoDisponibleException.class);
        assertThatThrownBy(() -> servicio.anadir(ID_CLIENTE, "no-es-un-id"))
                .isInstanceOf(ProductoNoDisponibleException.class);
        verify(repositorioFavorito, never()).save(any());
    }

    // ---------- HU-31.3 Eliminar ----------

    @Test
    void eliminar_productoDeSusFavoritos_loBorraSoloDeLosSuyos() {
        when(repositorioFavorito.borrar(new ObjectId(ID_CLIENTE), new ObjectId(ID_TAZA))).thenReturn(1L);

        servicio.eliminar(ID_CLIENTE, ID_TAZA);

        verify(repositorioFavorito).borrar(new ObjectId(ID_CLIENTE), new ObjectId(ID_TAZA));
    }

    @Test
    void eliminar_productoQueNoEstabaEnSusFavoritos_lanzaFavoritoNoEncontrado() {
        when(repositorioFavorito.borrar(new ObjectId(ID_CLIENTE), new ObjectId(ID_TAZA))).thenReturn(0L);

        assertThatThrownBy(() -> servicio.eliminar(ID_CLIENTE, ID_TAZA))
                .isInstanceOf(FavoritoNoEncontradoException.class);
    }

    @Test
    void eliminar_conIdMalFormado_lanzaFavoritoNoEncontradoSinTocarLaBaseDeDatos() {
        assertThatThrownBy(() -> servicio.eliminar(ID_CLIENTE, "no-es-un-id"))
                .isInstanceOf(FavoritoNoEncontradoException.class);
        verify(repositorioFavorito, never()).borrar(any(), any());
    }
}
