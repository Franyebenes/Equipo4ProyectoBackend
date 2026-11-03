package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.esibuy.esibuy_backend.dto.ProductoClienteDTO;
import com.esibuy.esibuy_backend.excepcion.FavoritoNoEncontradoException;
import com.esibuy.esibuy_backend.excepcion.FavoritoRepetidoException;
import com.esibuy.esibuy_backend.excepcion.ProductoNoDisponibleException;
import com.esibuy.esibuy_backend.modelo.ListaProductos;
import com.esibuy.esibuy_backend.modelo.Producto;
import com.esibuy.esibuy_backend.repositorio.RepositorioListaProductos;
import com.esibuy.esibuy_backend.repositorio.RepositorioProducto;

/**
 * HU-31.1 Ver, HU-31.2 Anadir y HU-31.3 Eliminar favoritos: siempre los del cliente de la sesion, sin repetir y solo
 * productos que el cliente puede ver. Se guardan en la lista Favoritos de Mis listas.
 */
@ExtendWith(MockitoExtension.class)
class ServicioFavoritosTest {

    private static final String ID_CLIENTE = "64b7f0c2a1b2c3d4e5f60001";
    private static final String ID_TAZA = "64b7f0c2a1b2c3d4e5f60101";
    private static final String ID_VASO = "64b7f0c2a1b2c3d4e5f60102";

    @Mock
    private ServicioListas servicioListas;
    @Mock
    private RepositorioListaProductos repositorioListas;
    @Mock
    private RepositorioProducto repositorioProducto;
    @Mock
    private ServicioCatalogo servicioCatalogo;

    private ServicioFavoritos servicio;
    private ListaProductos favoritos;

    @BeforeEach
    void crearServicio() {
        servicio = new ServicioFavoritos(servicioListas, repositorioListas, repositorioProducto, servicioCatalogo);
        favoritos = ListaProductos.favoritos(ID_CLIENTE);
        lenient().when(servicioListas.favoritos(ID_CLIENTE)).thenReturn(favoritos);
    }

    private void productoEnBaseDeDatos(String id, boolean activo) {
        when(repositorioProducto.findById(id))
                .thenReturn(Optional.of(Producto.builder().id(id).nombre("Taza").visible(activo).build()));
    }

    // ---------- HU-31.1 Ver ----------

    @Test
    void listar_devuelveSusFavoritosLosUltimosAnadidosPrimero() {
        // Given: añadió la taza antes que el vaso
        favoritos.anadirProducto(ID_TAZA);
        favoritos.anadirProducto(ID_VASO);
        List<ProductoClienteDTO> productos = List.of();
        when(servicioCatalogo.productosActivos(List.of(ID_VASO, ID_TAZA), false)).thenReturn(productos);

        // When / Then: con la informacion del catalogo, en ese orden
        assertThat(servicio.listar(ID_CLIENTE, false)).isSameAs(productos);
    }

    @Test
    void listar_sinFavoritos_pideUnaListaVacia() {
        when(servicioCatalogo.productosActivos(List.of(), true)).thenReturn(List.of());

        assertThat(servicio.listar(ID_CLIENTE, true)).isEmpty();
    }

    @Test
    void idsFavoritos_devuelveLosIdsDeSusProductosFavoritos() {
        favoritos.anadirProducto(ID_TAZA);

        assertThat(servicio.idsFavoritos(ID_CLIENTE)).containsExactly(ID_TAZA);
    }

    // ---------- HU-31.2 Anadir ----------

    @Test
    void anadir_productoActivo_loGuardaEnSuListaFavoritos() {
        productoEnBaseDeDatos(ID_TAZA, true);

        servicio.anadir(ID_CLIENTE, ID_TAZA);

        assertThat(favoritos.getIdProductos()).containsExactly(ID_TAZA);
        verify(repositorioListas).save(favoritos);
    }

    @Test
    void anadir_productoQueYaEsFavorito_lanzaFavoritoRepetidoYNoLoRepite() {
        productoEnBaseDeDatos(ID_TAZA, true);
        favoritos.anadirProducto(ID_TAZA);

        assertThatThrownBy(() -> servicio.anadir(ID_CLIENTE, ID_TAZA)).isInstanceOf(FavoritoRepetidoException.class);
        assertThat(favoritos.getIdProductos()).containsExactly(ID_TAZA);
        verify(repositorioListas, never()).save(any());
    }

    @Test
    void anadir_productoInactivo_lanzaProductoNoDisponible() {
        productoEnBaseDeDatos(ID_TAZA, false);

        assertThatThrownBy(() -> servicio.anadir(ID_CLIENTE, ID_TAZA))
                .isInstanceOf(ProductoNoDisponibleException.class);
        verify(repositorioListas, never()).save(any());
    }

    @Test
    void anadir_productoQueNoExisteOIdMalFormado_lanzaProductoNoDisponible() {
        when(repositorioProducto.findById(ID_TAZA)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.anadir(ID_CLIENTE, ID_TAZA))
                .isInstanceOf(ProductoNoDisponibleException.class);
        assertThatThrownBy(() -> servicio.anadir(ID_CLIENTE, "no-es-un-id"))
                .isInstanceOf(ProductoNoDisponibleException.class);
        verify(repositorioListas, never()).save(any());
    }

    // ---------- HU-31.3 Eliminar ----------

    @Test
    void eliminar_productoDeSusFavoritos_loQuitaDeSuLista() {
        favoritos.anadirProducto(ID_TAZA);
        favoritos.anadirProducto(ID_VASO);

        servicio.eliminar(ID_CLIENTE, ID_TAZA);

        assertThat(favoritos.getIdProductos()).containsExactly(ID_VASO);
        verify(repositorioListas).save(favoritos);
    }

    @Test
    void eliminar_productoQueNoEstabaEnSusFavoritos_lanzaFavoritoNoEncontrado() {
        assertThatThrownBy(() -> servicio.eliminar(ID_CLIENTE, ID_TAZA))
                .isInstanceOf(FavoritoNoEncontradoException.class);
        verify(repositorioListas, never()).save(any());
    }

    @Test
    void eliminar_conIdMalFormado_lanzaFavoritoNoEncontradoSinTocarLaBaseDeDatos() {
        assertThatThrownBy(() -> servicio.eliminar(ID_CLIENTE, "no-es-un-id"))
                .isInstanceOf(FavoritoNoEncontradoException.class);
        verify(servicioListas, never()).favoritos(any());
    }
}
