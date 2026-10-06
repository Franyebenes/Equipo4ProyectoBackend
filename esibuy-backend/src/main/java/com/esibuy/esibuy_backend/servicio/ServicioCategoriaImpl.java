package com.esibuy.esibuy_backend.servicio;

import java.text.Collator;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.esibuy.esibuy_backend.dto.CategoriaDTO;
import com.esibuy.esibuy_backend.modelo.Categoria;
import com.esibuy.esibuy_backend.repositorio.RepositorioCategoria;

/** Categorias del catalogo: listado (registro de vendedor y panel de administracion) y alta. */
@Service
public class ServicioCategoriaImpl implements ServicioCategoria {

    private final RepositorioCategoria repositorioCategoria;

    public ServicioCategoriaImpl(RepositorioCategoria repositorioCategoria) {
        this.repositorioCategoria = repositorioCategoria;
    }

    // Se ordena en Java con las reglas del espanol.
    // Sin @PreAuthorize: tambien lo usa el formulario publico de registro de vendedor.
    @Override
    public List<CategoriaDTO> listarCategorias() {
        Collator ordenEspanol = Collator.getInstance(Locale.of("es", "ES"));
        return repositorioCategoria.findAll().stream()
                .map(this::aDTO)
                .sorted(Comparator.comparing(CategoriaDTO::nombre, ordenEspanol))
                .toList();
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public CategoriaDTO crearCategoria(CategoriaDTO dto) {
        String nombre = dto.nombre().trim();
        String descripcion = dto.descripcion().trim();

        if (repositorioCategoria.existsByNombreIgnoreCase(nombre)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ya existe una categoría con ese nombre");
        }

        try {
            Categoria guardada = repositorioCategoria.save(new Categoria(null, nombre, descripcion));
            return aDTO(guardada);
        } catch (DataAccessException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "No se ha podido crear la categoría, inténtalo de nuevo más tarde", e);
        }
    }

    private CategoriaDTO aDTO(Categoria categoria) {
        return new CategoriaDTO(categoria.getId(), categoria.getNombre(), categoria.getDescripcion());
    }
}