package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import com.esibuy.esibuy_backend.excepcion.CategoriaConProductosException;
import com.esibuy.esibuy_backend.excepcion.CategoriaNoEncontradaException;
import com.esibuy.esibuy_backend.modelo.Categoria;
import com.esibuy.esibuy_backend.repositorio.RepositorioCategoria;

/**
 * Eliminacion de categorias: borrado correcto, categoria con productos asociados y categoria inexistente.
 */
@ExtendWith(MockitoExtension.class)
class ServicioCategoriaEliminarTest {

    private static final String ID = "64b7f0c2a1b2c3d4e5f60718";

    @Mock
    private RepositorioCategoria repositorioCategoria;
    @Mock
    private MongoTemplate mongoTemplate;
    @InjectMocks
    private ServicioCategoriaImpl servicio;

    @Test
    void eliminarCategoria_sinProductos_laBorraYConsultaLaColeccionDeProductos() {
        // Given: la categoria existe y no tiene productos
        when(repositorioCategoria.findById(ID)).thenReturn(Optional.of(new Categoria(ID, "Moda", "Ropa y complementos.")));
        when(mongoTemplate.count(any(Query.class), eq("productos"))).thenReturn(0L);

        // When
        servicio.eliminarCategoria(ID);

        // Then: se borra, y la consulta respeta el contrato con productos (campo categoriaId)
        verify(repositorioCategoria).deleteById(ID);
        ArgumentCaptor<Query> consulta = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).count(consulta.capture(), eq("productos"));
        assertThat(consulta.getValue().getQueryObject()).containsEntry("categoriaId", ID);
    }

    @Test
    void eliminarCategoria_conProductos_lanzaExcepcionConElMotivoYNoLaBorra() {
        // Given: la categoria tiene 3 productos asociados
        when(repositorioCategoria.findById(ID)).thenReturn(Optional.of(new Categoria(ID, "Moda", "Ropa y complementos.")));
        when(mongoTemplate.count(any(Query.class), eq("productos"))).thenReturn(3L);

        // When / Then
        assertThatThrownBy(() -> servicio.eliminarCategoria(ID))
                .isInstanceOf(CategoriaConProductosException.class)
                .hasMessage("No se puede eliminar la categoría «Moda» porque tiene 3 productos asociados");
        verify(repositorioCategoria, never()).deleteById(any());
    }

    @Test
    void eliminarCategoria_conUnProducto_usaElSingularEnElMensaje() {
        // Given
        when(repositorioCategoria.findById(ID)).thenReturn(Optional.of(new Categoria(ID, "Moda", "Ropa y complementos.")));
        when(mongoTemplate.count(any(Query.class), eq("productos"))).thenReturn(1L);

        // When / Then
        assertThatThrownBy(() -> servicio.eliminarCategoria(ID))
                .hasMessage("No se puede eliminar la categoría «Moda» porque tiene 1 producto asociado");
    }

    @Test
    void eliminarCategoria_inexistente_lanzaExcepcionYNoConsultaProductosNiBorra() {
        // Given: no existe ninguna categoria con ese id
        when(repositorioCategoria.findById(ID)).thenReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> servicio.eliminarCategoria(ID))
                .isInstanceOf(CategoriaNoEncontradaException.class);
        verify(mongoTemplate, never()).count(any(Query.class), any(String.class));
        verify(repositorioCategoria, never()).deleteById(any());
    }
}