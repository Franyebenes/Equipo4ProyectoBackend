package com.esibuy.esibuy_backend.servicio;

import java.util.HashMap;
import java.util.IntSummaryStatistics;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Collation;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import com.esibuy.esibuy_backend.dto.Disponibilidad;
import com.esibuy.esibuy_backend.dto.PaginaDTO;
import com.esibuy.esibuy_backend.dto.ProductoAdminDTO;
import com.esibuy.esibuy_backend.dto.ProductoAdminDetalleDTO;
import com.esibuy.esibuy_backend.excepcion.ProductoNoEncontradoException;
import com.esibuy.esibuy_backend.modelo.PrecioProducto;
import com.esibuy.esibuy_backend.modelo.Producto;
import com.esibuy.esibuy_backend.modelo.Valoracion;
import com.esibuy.esibuy_backend.repositorio.RepositorioCategoria;
import com.esibuy.esibuy_backend.repositorio.RepositorioProducto;
import com.esibuy.esibuy_backend.repositorio.RepositorioUsuario;
import com.esibuy.esibuy_backend.repositorio.RepositorioValoracion;

/**
 * Modo lectura de todos los productos para el administrador, con los filtros del catalogo de los clientes. A
 * diferencia de ese catalogo, incluye los productos desactivados y los solo premium.
 */
@Service
public class ServicioProductoAdmin {

    static final int TAMANO_MAXIMO = 50;
    static final String VENDEDOR_DESCONOCIDO = "(vendedor eliminado)";

    private final MongoTemplate mongoTemplate;
    private final RepositorioUsuario repositorioUsuario;
    private final RepositorioCategoria repositorioCategoria;
    private final RepositorioProducto repositorioProducto;
    private final RepositorioValoracion repositorioValoracion;

    public ServicioProductoAdmin(MongoTemplate mongoTemplate, RepositorioUsuario repositorioUsuario,
                                 RepositorioCategoria repositorioCategoria, RepositorioProducto repositorioProducto,
                                 RepositorioValoracion repositorioValoracion) {
        this.mongoTemplate = mongoTemplate;
        this.repositorioUsuario = repositorioUsuario;
        this.repositorioCategoria = repositorioCategoria;
        this.repositorioProducto = repositorioProducto;
        this.repositorioValoracion = repositorioValoracion;
    }

    public PaginaDTO<ProductoAdminDTO> listar(FiltroCatalogoCliente filtro, int pagina, int tamano) {
        filtro.validar();
        int paginaSegura = Math.max(pagina, 0);
        int tamanoSeguro = Math.clamp(tamano, 1, TAMANO_MAXIMO);

        // Sin filtro por "visible" ni "soloPremium": el admin lo ve todo. Las imagenes (base64) se piden solo para la
        // pagina que se muestra
        Query consulta = new Query().with(Sort.by("nombre")).collation(Collation.of("es"));
        consulta.fields().exclude("imagen");

        Map<String, IntSummaryStatistics> puntuaciones = repositorioValoracion.findAll().stream()
                .collect(Collectors.groupingBy(Valoracion::getIdProducto,
                        Collectors.summarizingInt(Valoracion::getPuntos)));
        FiltroCatalogo porCategoriaYPrecio = new FiltroCatalogo(null, filtro.idCategoria(), null,
                filtro.precioMinimo(), filtro.precioMaximo());
        List<Producto> todos = mongoTemplate.find(consulta, Producto.class);
        Map<String, String> vendedores = nombresVendedores(todos);
        List<Producto> encontrados = todos.stream()
                .filter(porCategoriaYPrecio::admite)
                .filter(p -> FiltroCatalogo.algunoContiene(filtro.busqueda(), p.getNombre(),
                        vendedores.get(p.getIdVendedor())))
                .filter(p -> cumpleValoracion(puntuaciones.get(p.getId()), filtro))
                .filter(p -> !filtro.soloDisponibles() || Disponibilidad.de(p.getStock()) != Disponibilidad.AGOTADO)
                .toList();

        List<Producto> productos = encontrados.stream()
                .skip((long) paginaSegura * tamanoSeguro)
                .limit(tamanoSeguro)
                .toList();
        Map<String, String> imagenes = imagenesDe(productos);
        Map<String, String> categorias = nombresCategorias(productos);

        List<ProductoAdminDTO> contenido = productos.stream()
                .map(p -> aDTO(p, imagenes, vendedores, categorias, puntuaciones.get(p.getId())))
                .toList();
        int totalPaginas = (int) Math.ceil((double) encontrados.size() / tamanoSeguro);
        return new PaginaDTO<>(contenido, paginaSegura, tamanoSeguro, encontrados.size(), totalPaginas);
    }

    /** Ficha de un producto, tambien si esta desactivado. Si no existe, 404. */
    public ProductoAdminDetalleDTO obtener(String id) {
        if (id == null || !ObjectId.isValid(id)) {
            throw new ProductoNoEncontradoException();
        }
        Producto p = repositorioProducto.findById(id).orElseThrow(ProductoNoEncontradoException::new);
        List<Producto> producto = List.of(p);
        Map<String, String> categorias = nombresCategorias(producto);
        IntSummaryStatistics puntuacion = repositorioValoracion.buscarPorProducto(new ObjectId(id)).stream()
                .collect(Collectors.summarizingInt(Valoracion::getPuntos));
        PrecioProducto precio = p.getPrecio();
        boolean conPrecio = precio != null && precio.getBase() != null;
        return new ProductoAdminDetalleDTO(
                p.getId(),
                p.getNombre(),
                p.getDescripcion(),
                p.getImagen(),
                nombresVendedores(producto).getOrDefault(p.getIdVendedor(), VENDEDOR_DESCONOCIDO),
                categoriasDe(p).stream().map(categorias::get).filter(Objects::nonNull).toList(),
                conPrecio ? precio.getBase() : null,
                conPrecio ? precio.precioFinal(false) : null,
                conPrecio ? precio.precioFinal(true) : null,
                precio == null ? null : precio.getDescuento(),
                precio == null ? null : precio.getDescuentoPremium(),
                p.getStock() == null ? 0 : p.getStock(),
                Boolean.TRUE.equals(p.getVisible()),
                Boolean.TRUE.equals(p.getSoloPremium()),
                puntuacion.getCount() == 0 ? null : puntuacion.getAverage(),
                (int) puntuacion.getCount());
    }

    private static ProductoAdminDTO aDTO(Producto p, Map<String, String> imagenes, Map<String, String> vendedores,
                                         Map<String, String> categorias, IntSummaryStatistics puntuacion) {
        PrecioProducto precio = p.getPrecio();
        boolean conPrecio = precio != null && precio.getBase() != null;
        return new ProductoAdminDTO(
                p.getId(),
                p.getNombre(),
                imagenes.get(p.getId()),
                vendedores.getOrDefault(p.getIdVendedor(), VENDEDOR_DESCONOCIDO),
                categoriasDe(p).stream().map(categorias::get).filter(Objects::nonNull).toList(),
                conPrecio ? precio.getBase() : null,
                conPrecio ? precio.precioFinal(false) : null,
                p.getStock() == null ? 0 : p.getStock(),
                Boolean.TRUE.equals(p.getVisible()),
                Boolean.TRUE.equals(p.getSoloPremium()),
                puntuacion == null ? null : puntuacion.getAverage(),
                puntuacion == null ? 0 : (int) puntuacion.getCount());
    }

    private static boolean cumpleValoracion(IntSummaryStatistics puntuacion, FiltroCatalogoCliente filtro) {
        return filtro.valoracionMinima() == null
                || (puntuacion != null && puntuacion.getAverage() >= filtro.valoracionMinima());
    }

    private Map<String, String> imagenesDe(List<Producto> productos) {
        if (productos.isEmpty()) {
            return Map.of();
        }
        return repositorioProducto.findAllById(productos.stream().map(Producto::getId).toList()).stream()
                .filter(p -> p.getImagen() != null)
                .collect(Collectors.toMap(Producto::getId, Producto::getImagen));
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
