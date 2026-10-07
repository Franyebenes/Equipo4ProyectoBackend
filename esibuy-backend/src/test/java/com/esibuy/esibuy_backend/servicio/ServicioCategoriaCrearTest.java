package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.esibuy.esibuy_backend.dto.CategoriaDTO;
import com.esibuy.esibuy_backend.modelo.Categoria;
import com.esibuy.esibuy_backend.repositorio.RepositorioCategoria;

/**
 * HU-11.2 Creacion de categorias: alta correcta, nombre repetido y fallo de la base de datos.
 */
@ExtendWith(MockitoExtension.class)
class ServicioCategoriaCrearTest {

    private static final String ID = "64b7f0c2a1b2c3d4e5f60718";

    @Mock
    private RepositorioCategoria repositorioCategoria;
    @InjectMocks
    private ServicioCategoriaImpl servicio;

    @Test
    void crearCategoria_datosValidos_guardaSinEspaciosYDevuelveElDTOConId() {
        // Given: el nombre no existe todavia y MongoDB asigna un id al guardar
        when(repositorioCategoria.existsByNombreIgnoreCase("Videojuegos")).thenReturn(false);
        when(repositorioCategoria.save(any(Categoria.class)))
                .thenReturn(new Categoria(ID, "Videojuegos", "Consolas y juegos."));

        // When: llegan con espacios sobrantes
        CategoriaDTO resultado = servicio.crearCategoria(
                new CategoriaDTO(null, "  Videojuegos  ", "  Consolas y juegos.  "));

        // Then: se guarda limpia y sin id (lo genera MongoDB), y se devuelve con el id asignado
        ArgumentCaptor<Categoria> guardada = ArgumentCaptor.forClass(Categoria.class);
        verify(repositorioCategoria).save(guardada.capture());
        assertThat(guardada.getValue().getId()).isNull();
        assertThat(guardada.getValue().getNombre()).isEqualTo("Videojuegos");
        assertThat(guardada.getValue().getDescripcion()).isEqualTo("Consolas y juegos.");
        assertThat(resultado.id()).isEqualTo(ID);
        assertThat(resultado.nombre()).isEqualTo("Videojuegos");
        assertThat(resultado.descripcion()).isEqualTo("Consolas y juegos.");
    }

    @Test
    void crearCategoria_nombreRepetido_lanzaBadRequestYNoGuarda() {
        // Given: ya existe una categoria con ese nombre (sin distinguir mayusculas)
        when(repositorioCategoria.existsByNombreIgnoreCase("Moda")).thenReturn(true);
        CategoriaDTO dto = new CategoriaDTO(null, "Moda", "Ropa y complementos.");

        // When / Then
        assertThatThrownBy(() -> servicio.crearCategoria(dto))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("Ya existe una categoría con ese nombre");
        verify(repositorioCategoria, never()).save(any());
    }

    @Test
    void crearCategoria_fallaLaBaseDeDatos_lanzaInternalServerError() {
        // Given: el nombre esta libre pero MongoDB no responde al guardar
        when(repositorioCategoria.existsByNombreIgnoreCase("Hogar")).thenReturn(false);
        when(repositorioCategoria.save(any(Categoria.class)))
                .thenThrow(new DataAccessResourceFailureException("sin conexion"));
        CategoriaDTO dto = new CategoriaDTO(null, "Hogar", "Muebles y decoración.");

        // When / Then
        assertThatThrownBy(() -> servicio.crearCategoria(dto))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR));
    }
}