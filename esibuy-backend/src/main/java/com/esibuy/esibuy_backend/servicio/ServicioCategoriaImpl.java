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
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import com.esibuy.esibuy_backend.dto.CategoriaDTO;
import com.esibuy.esibuy_backend.modelo.Categoria;
import com.esibuy.esibuy_backend.repositorio.RepositorioCategoria;
import com.esibuy.esibuy_backend.excepcion.CategoriaConProductosException;
import com.esibuy.esibuy_backend.excepcion.CategoriaNoEncontradaException;

/** Categorias del catalogo: listado (registro de vendedor y panel de administracion) y alta. */
@Service
public class ServicioCategoriaImpl implements ServicioCategoria {

        // Contrato con la HU de productos: colección "productos", campo "categoriaId" (String con el id de la categoría)
    private static final String COLECCION_PRODUCTOS = "productos";
    private static final String CAMPO_CATEGORIA_PRODUCTO = "categoriaId";

    private final RepositorioCategoria repositorioCategoria;
    private final MongoTemplate mongoTemplate;

    public ServicioCategoriaImpl(RepositorioCategoria repositorioCategoria, MongoTemplate mongoTemplate) {
        this.repositorioCategoria = repositorioCategoria;
        this.mongoTemplate = mongoTemplate;
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


        @Override
    @PreAuthorize("hasRole('ADMIN')")
    public void eliminarCategoria(String id) {
        Categoria categoria = repositorioCategoria.findById(id)
                .orElseThrow(CategoriaNoEncontradaException::new);

        long productosAsociados = mongoTemplate.count(
                Query.query(Criteria.where(CAMPO_CATEGORIA_PRODUCTO).is(id)), COLECCION_PRODUCTOS);
        if (productosAsociados > 0) {
            throw new CategoriaConProductosException(categoria.getNombre(), productosAsociados);
        }

        repositorioCategoria.deleteById(id);
    }

    private CategoriaDTO aDTO(Categoria categoria) {
        return new CategoriaDTO(categoria.getId(), categoria.getNombre(), categoria.getDescripcion());
    }
}