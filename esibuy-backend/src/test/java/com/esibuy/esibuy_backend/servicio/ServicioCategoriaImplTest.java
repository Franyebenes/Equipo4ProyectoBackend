package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.esibuy.esibuy_backend.dto.CategoriaDTO;
import com.esibuy.esibuy_backend.modelo.Categoria;
import com.esibuy.esibuy_backend.repositorio.RepositorioCategoria;

/**
 * Listado de categorias para el registro de vendedor.
 */
@ExtendWith(MockitoExtension.class)
class ServicioCategoriaImplTest {

    @Mock
    private RepositorioCategoria repositorioCategoria;
    @InjectMocks
    private ServicioCategoriaImpl servicio;

    @Test
    void listarCategorias_devuelveIdYNombreOrdenadosAlfabeticamenteEnEspanol() {
        // Given: MongoDB las devuelve sin orden y una empieza por letra con tilde
        when(repositorioCategoria.findAll()).thenReturn(List.of(
                                new Categoria("3", "Moda", "Ropa y complementos."),
                new Categoria("1", "Électronica", "Móviles y ordenadores."),
                new Categoria("2", "Hogar", "Muebles y decoración."),
                new Categoria("4", "Alimentación", "Comida y bebida.")));

        // When
        List<CategoriaDTO> categorias = servicio.listarCategorias();

        // Then: la tilde no manda la categoria al final
        assertThat(categorias).extracting(CategoriaDTO::nombre)
                .containsExactly("Alimentación", "Électronica", "Hogar", "Moda");
        assertThat(categorias.get(0).id()).isEqualTo("4");
        assertThat(categorias.get(0).descripcion()).isEqualTo("Comida y bebida.");
    }

    @Test
    void listarCategorias_sinCategorias_devuelveListaVacia() {
        // Given
        when(repositorioCategoria.findAll()).thenReturn(List.of());

        // When / Then
        assertThat(servicio.listarCategorias()).isEmpty();
    }
}
