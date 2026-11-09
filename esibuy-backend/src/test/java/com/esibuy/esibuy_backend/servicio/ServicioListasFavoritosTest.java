package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;

import com.esibuy.esibuy_backend.modelo.Favorito;
import com.esibuy.esibuy_backend.modelo.ListaProductos;
import com.esibuy.esibuy_backend.repositorio.RepositorioFavorito;
import com.esibuy.esibuy_backend.repositorio.RepositorioListaProductos;

/** La lista Favoritos de Mis listas recoge los favoritos guardados con el sistema antiguo (coleccion "favoritos"). */
@ExtendWith(MockitoExtension.class)
class ServicioListasFavoritosTest {

    private static final String ID_CLIENTE = "64b7f0c2a1b2c3d4e5f60001";
    private static final ObjectId CLIENTE = new ObjectId(ID_CLIENTE);
    private static final String ID_TAZA = "64b7f0c2a1b2c3d4e5f60101";
    private static final String ID_VASO = "64b7f0c2a1b2c3d4e5f60102";
    private static final Instant AHORA = Instant.parse("2026-10-26T12:00:00Z");

    @Mock
    private RepositorioListaProductos repositorio;
    @Mock
    private RepositorioFavorito repositorioFavorito;
    @Mock
    private MongoTemplate mongoTemplate;

    private ServicioListas servicio;
    private ListaProductos favoritos;

    @BeforeEach
    void crearServicio() {
        servicio = new ServicioListas(repositorio, repositorioFavorito, mongoTemplate);
        favoritos = ListaProductos.favoritos(ID_CLIENTE);
        when(repositorio.existeFavoritos(CLIENTE)).thenReturn(true);
        when(repositorio.buscarFavoritos(CLIENTE)).thenReturn(Optional.of(favoritos));
    }

    @Test
    void favoritos_conFavoritosAntiguos_losPasaALaListaEnOrdenYLosBorra() {
        // Given: añadió el vaso antes que la taza con el sistema antiguo
        List<Favorito> antiguos = List.of(new Favorito(ID_CLIENTE, ID_TAZA, AHORA),
                new Favorito(ID_CLIENTE, ID_VASO, AHORA.minusSeconds(60)));
        when(repositorioFavorito.buscarPorCliente(CLIENTE)).thenReturn(antiguos);

        // When
        ListaProductos lista = servicio.favoritos(ID_CLIENTE);

        // Then
        assertThat(lista.getIdProductos()).containsExactly(ID_VASO, ID_TAZA);
        verify(repositorio).save(favoritos);
        verify(repositorioFavorito).deleteAll(antiguos);
    }

    @Test
    void favoritos_sinFavoritosAntiguos_noGuardaNada() {
        when(repositorioFavorito.buscarPorCliente(CLIENTE)).thenReturn(List.of());

        assertThat(servicio.favoritos(ID_CLIENTE)).isSameAs(favoritos);
        verify(repositorio, never()).save(any());
        verify(repositorioFavorito, never()).deleteAll(any());
    }
}
