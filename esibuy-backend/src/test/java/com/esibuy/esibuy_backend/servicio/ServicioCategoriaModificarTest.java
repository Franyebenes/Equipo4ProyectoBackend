package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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

import com.esibuy.esibuy_backend.dto.CategoriaDTO;
import com.esibuy.esibuy_backend.excepcion.CategoriaDuplicadaException;
import com.esibuy.esibuy_backend.excepcion.CategoriaNoEncontradaException;
import com.esibuy.esibuy_backend.modelo.Categoria;
import com.esibuy.esibuy_backend.repositorio.RepositorioCategoria;

/**
 * HU-11.3 Modificacion de categorias: carga de los datos actuales, guardado correcto, nombre repetido y
 * categoria inexistente.
 */
@ExtendWith(MockitoExtension.class)
class ServicioCategoriaModificarTest {

    private static final String ID = "64b7f0c2a1b2c3d4e5f60718";

    @Mock
    private RepositorioCategoria repositorioCategoria;
    @InjectMocks
    private ServicioCategoriaImpl servicio;

    @Test
    void obtenerCategoria_existente_devuelveSusDatosActuales() {
        // Given
        when(repositorioCategoria.findById(ID)).thenReturn(Optional.of(new Categoria(ID, "Moda", "Ropa y complementos.")));

        // When
        CategoriaDTO resultado = servicio.obtenerCategoria(ID);

        // Then
        assertThat(resultado).isEqualTo(new CategoriaDTO(ID, "Moda", "Ropa y complementos."));
    }

    @Test
    void obtenerCategoria_inexistente_lanzaExcepcion() {
        // Given: no existe ninguna categoria con ese id
        when(repositorioCategoria.findById(ID)).thenReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> servicio.obtenerCategoria(ID))
                .isInstanceOf(CategoriaNoEncontradaException.class);
    }

    @Test
    void modificarCategoria_datosValidos_guardaSinEspaciosConElMismoIdYDevuelveLosNuevosDatos() {
        // Given: la categoria existe y el nuevo nombre no lo usa ninguna otra
        when(repositorioCategoria.findById(ID)).thenReturn(Optional.of(new Categoria(ID, "Moda", "Ropa y complementos.")));
        when(repositorioCategoria.existsByNombreIgnoreCaseAndIdNot("Moda y calzado", ID)).thenReturn(false);
        when(repositorioCategoria.save(any(Categoria.class))).thenAnswer(invocacion -> invocacion.getArgument(0));

        // When: llegan con espacios sobrantes
        CategoriaDTO resultado = servicio.modificarCategoria(ID,
                new CategoriaDTO(null, "  Moda y calzado  ", "  Ropa, zapatos y complementos.  "));

        // Then: se guarda limpia sobre la misma categoria (no se crea otra) y se devuelve con los nuevos datos
        ArgumentCaptor<Categoria> guardada = ArgumentCaptor.forClass(Categoria.class);
        verify(repositorioCategoria).save(guardada.capture());
        assertThat(guardada.getValue().getId()).isEqualTo(ID);
        assertThat(guardada.getValue().getNombre()).isEqualTo("Moda y calzado");
        assertThat(guardada.getValue().getDescripcion()).isEqualTo("Ropa, zapatos y complementos.");
        assertThat(resultado).isEqualTo(new CategoriaDTO(ID, "Moda y calzado", "Ropa, zapatos y complementos."));
    }

    @Test
    void modificarCategoria_soloCambiaLaDescripcionOLasMayusculas_noSeConsideraNombreRepetido() {
        // Given: la unica categoria con ese nombre es ella misma, asi que la consulta que la excluye no encuentra otra
        when(repositorioCategoria.findById(ID)).thenReturn(Optional.of(new Categoria(ID, "moda", "Ropa.")));
        when(repositorioCategoria.existsByNombreIgnoreCaseAndIdNot("Moda", ID)).thenReturn(false);
        when(repositorioCategoria.save(any(Categoria.class))).thenAnswer(invocacion -> invocacion.getArgument(0));

        // When
        CategoriaDTO resultado = servicio.modificarCategoria(ID, new CategoriaDTO(null, "Moda", "Ropa y complementos."));

        // Then
        assertThat(resultado.nombre()).isEqualTo("Moda");
        assertThat(resultado.descripcion()).isEqualTo("Ropa y complementos.");
    }

    @Test
    void modificarCategoria_nombreDeOtraCategoria_lanzaExcepcionConElMotivoYNoGuarda() {
        // Given: otra categoria ya se llama "Hogar" (sin distinguir mayusculas)
        when(repositorioCategoria.findById(ID)).thenReturn(Optional.of(new Categoria(ID, "Moda", "Ropa y complementos.")));
        when(repositorioCategoria.existsByNombreIgnoreCaseAndIdNot("hogar", ID)).thenReturn(true);
        CategoriaDTO dto = new CategoriaDTO(null, "hogar", "Muebles y decoración.");

        // When / Then
        assertThatThrownBy(() -> servicio.modificarCategoria(ID, dto))
                .isInstanceOf(CategoriaDuplicadaException.class)
                .hasMessage("Ya existe una categoría con el nombre «hogar»");
        verify(repositorioCategoria, never()).save(any());
    }

    @Test
    void modificarCategoria_inexistente_lanzaExcepcionYNoGuarda() {
        // Given: la categoria se ha eliminado mientras se editaba
        when(repositorioCategoria.findById(ID)).thenReturn(Optional.empty());
        CategoriaDTO dto = new CategoriaDTO(null, "Moda", "Ropa y complementos.");

        // When / Then
        assertThatThrownBy(() -> servicio.modificarCategoria(ID, dto))
                .isInstanceOf(CategoriaNoEncontradaException.class);
        verify(repositorioCategoria, never()).existsByNombreIgnoreCaseAndIdNot(anyString(), anyString());
        verify(repositorioCategoria, never()).save(any());
    }
}
