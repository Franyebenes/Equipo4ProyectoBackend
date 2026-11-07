package com.esibuy.esibuy_backend.servicio;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Collation;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import com.esibuy.esibuy_backend.dto.PaginaDTO;
import com.esibuy.esibuy_backend.dto.ProductoAdminDTO;
import com.esibuy.esibuy_backend.modelo.Producto;
import com.esibuy.esibuy_backend.repositorio.RepositorioCategoria;
import com.esibuy.esibuy_backend.repositorio.RepositorioUsuario;

/** Modo lectura de todos los productos para el administrador . */
@Service
public class ServicioProductoAdmin {

    static final int TAMANO_MAXIMO = 50;
    static final String VENDEDOR_DESCONOCIDO = "(vendedor eliminado)";

    private final MongoTemplate mongoTemplate;
    private final RepositorioUsuario repositorioUsuario;
    private final RepositorioCategoria repositorioCategoria;

    public ServicioProductoAdmin(MongoTemplate mongoTemplate, RepositorioUsuario repositorioUsuario,
                                 RepositorioCategoria repositorioCategoria) {
        this.mongoTemplate = mongoTemplate;
        this.repositorioUsuario = repositorioUsuario;
        this.repositorioCategoria = repositorioCategoria;
    }

    public PaginaDTO<ProductoAdminDTO> listar(int pagina, int tamano) {
        int paginaSegura = Math.max(pagina, 0);
        int tamanoSeguro = Math.clamp(tamano, 1, TAMANO_MAXIMO);

        // Sin filtro por "visible" ni "soloPremium": el admin lo ve todo
        Query consulta = new Query()
                .with(PageRequest.of(paginaSegura, tamanoSeguro, Sort.by("nombre")))
                .collation(Collation.of("es"));
        consulta.fields().exclude("imagen"); // No descargar las imagenes en base64

        List<Producto> productos = mongoTemplate.find(consulta, Producto.class);
        long total = mongoTemplate.count(new Query(), Producto.class);

        Map<String, String> vendedores = nombresVendedores(productos);
        Map<String, String> categorias = nombresCategorias(productos);

        List<ProductoAdminDTO> contenido = productos.stream()
                .map(p -> new ProductoAdminDTO(
                        p.getId(),
                        p.getNombre(),
                        vendedores.getOrDefault(p.getIdVendedor(), VENDEDOR_DESCONOCIDO),
                        categoriasDe(p).stream().map(categorias::get).filter(Objects::nonNull).toList(),
                        p.getPrecio() == null ? null : p.getPrecio().getBase(),
                        p.getStock() == null ? 0 : p.getStock(),
                        Boolean.TRUE.equals(p.getVisible()),
                        Boolean.TRUE.equals(p.getSoloPremium())))
                .toList();

        int totalPaginas = (int) Math.ceil((double) total / tamanoSeguro);
        return new PaginaDTO<>(contenido, paginaSegura, tamanoSeguro, total, totalPaginas);
    }

    /** Una sola consulta para todos los vendedores de la pagina. */
    private Map<String, String> nombresVendedores(List<Producto> productos) {
        Set<String> ids = productos.stream().map(Producto::getIdVendedor).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, String> nombres = new HashMap<>();
        repositorioUsuario.findAllById(ids)
                .forEach(u -> nombres.put(u.getId(), u.getPerfil().getNombreComercial()));
        return nombres;
    }

    /** Una sola consulta para todas las categorias de la pagina. */
    private Map<String, String> nombresCategorias(List<Producto> productos) {
        Set<String> ids = productos.stream().flatMap(p -> categoriasDe(p).stream()).collect(Collectors.toSet());
        Map<String, String> nombres = new HashMap<>();
        repositorioCategoria.findAllById(ids).forEach(c -> nombres.put(c.getId(), c.getNombre()));
        return nombres;
    }

    private static List<String> categoriasDe(Producto producto) {
        return producto.getIdCategorias() == null ? List.of() : producto.getIdCategorias();
    }
}