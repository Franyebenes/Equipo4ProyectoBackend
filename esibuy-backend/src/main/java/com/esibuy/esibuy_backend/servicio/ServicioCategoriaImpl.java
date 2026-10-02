package com.esibuy.esibuy_backend.servicio;

import java.text.Collator;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;

import com.esibuy.esibuy_backend.dto.CategoriaDTO;
import com.esibuy.esibuy_backend.repositorio.RepositorioCategoria;

/** Categorias para el desplegable del registro de vendedor, ordenadas alfabeticamente. */
@Service
public class ServicioCategoriaImpl implements ServicioCategoria {

    private final RepositorioCategoria repositorioCategoria;

    public ServicioCategoriaImpl(RepositorioCategoria repositorioCategoria) {
        this.repositorioCategoria = repositorioCategoria;
    }

    // Se ordena en Java con las reglas del espanol
    @Override
    public List<CategoriaDTO> listarCategorias() {
        Collator ordenEspanol = Collator.getInstance(Locale.of("es", "ES"));
        return repositorioCategoria.findAll().stream()
                .map(categoria -> new CategoriaDTO(categoria.getId(), categoria.getNombre()))
                .sorted(Comparator.comparing(CategoriaDTO::nombre, ordenEspanol))
                .toList();
    }
}
