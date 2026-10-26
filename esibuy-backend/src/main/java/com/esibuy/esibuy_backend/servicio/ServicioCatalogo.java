package com.esibuy.esibuy_backend.servicio;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.bson.types.ObjectId;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.esibuy.esibuy_backend.dto.Disponibilidad;
import com.esibuy.esibuy_backend.dto.PaginaDTO;
import com.esibuy.esibuy_backend.dto.ProductoClienteDTO;
import com.esibuy.esibuy_backend.dto.ProductoDetalleDTO;
import com.esibuy.esibuy_backend.excepcion.ProductoNoDisponibleException;
import com.esibuy.esibuy_backend.modelo.Categoria;
import com.esibuy.esibuy_backend.modelo.PrecioProducto;
import com.esibuy.esibuy_backend.modelo.Producto;
import com.esibuy.esibuy_backend.modelo.Valoracion;
import com.esibuy.esibuy_backend.repositorio.RepositorioCategoria;
import com.esibuy.esibuy_backend.repositorio.RepositorioProducto;
import com.esibuy.esibuy_backend.repositorio.RepositorioValoracion;

@Service
public class ServicioCatalogo {

    /** Productos por pagina: 3 filas de 4 en la cuadricula del catalogo. */
    public static final int TAMANO_PAGINA = 12;
    private static final int PRIMERA_PAGINA = 0;
    // Producto sin valoraciones: media vacia y 0 valoraciones
    private static final Puntuacion SIN_VALORACIONES = new Puntuacion(null, 0);

    private final RepositorioProducto repositorioProducto;
    private final RepositorioCategoria repositorioCategoria;
    private final RepositorioValoracion repositorioValoracion;

    public ServicioCatalogo(RepositorioProducto repositorioProducto, RepositorioCategoria repositorioCategoria,
                            RepositorioValoracion repositorioValoracion) {
        this.repositorioProducto = repositorioProducto;
        this.repositorioCategoria = repositorioCategoria;
        this.repositorioValoracion = repositorioValoracion;
    }

    /** Media de estrellas de un producto y cuantas valoraciones tiene. */
    private record Puntuacion(Double media, int numero) {
    }

    /**
     * Una pagina del catalogo con la busqueda y los filtros aplicados (HU-19 y HU-20). La pagina empieza en 0; una
     * negativa se trata como la primera y una que no existe sale vacia. Si los filtros no tienen sentido, 400.
     *
     * @param clientePremium si es true, el precio final (y el filtro de precio) lleva tambien el descuento premium
     */
    public PaginaDTO<ProductoClienteDTO> buscar(FiltroCatalogoCliente filtro, int pagina, boolean clientePremium) {
        filtro.validar();
        FiltroCatalogo porNombre = new FiltroCatalogo(filtro.busqueda(), filtro.idCategoria(), null, null, null);
        Map<String, Puntuacion> puntuaciones = puntuacionesPorProducto(repositorioValoracion.findAll());
        // El id de MongoDB empieza por la fecha de creacion: ordenado al reves, los mas nuevos primero
        List<Producto> encontrados = repositorioProducto.buscarActivos().stream()
                .filter(porNombre::admite)
                .filter(producto -> cumplePrecio(producto, filtro, clientePremium))
                .filter(producto -> cumpleValoracion(puntuaciones.get(producto.getId()), filtro))
                .filter(producto -> !filtro.soloDisponibles()
                        || Disponibilidad.de(producto.getStock()) != Disponibilidad.AGOTADO)
                .sorted(Comparator.comparing(Producto::getId).reversed())
                .toList();

        int numeroPagina = Math.max(pagina, PRIMERA_PAGINA);
        long desde = (long) numeroPagina * TAMANO_PAGINA;
        List<Producto> dePagina = encontrados.stream().skip(desde).limit(TAMANO_PAGINA).toList();

        Map<String, String> imagenes = imagenesDe(dePagina);
        Map<String, String> nombresCategorias = nombresDeCategorias(dePagina);
        List<ProductoClienteDTO> contenido = dePagina.stream()
                .map(producto -> aDTO(producto, imagenes, nombresCategorias, clientePremium,
                        puntuaciones.getOrDefault(producto.getId(), SIN_VALORACIONES)))
                .toList();
        return PaginaDTO.desde(
                new PageImpl<>(contenido, PageRequest.of(numeroPagina, TAMANO_PAGINA), encontrados.size()));
    }

    public List<ProductoClienteDTO> productosActivos(List<String> idsProductos, boolean clientePremium) {
        if (idsProductos.isEmpty()) {
            return List.of();
        }
        Map<String, Producto> porId = repositorioProducto.findAllById(idsProductos).stream()
                .filter(producto -> Boolean.TRUE.equals(producto.getVisible()))
                .collect(Collectors.toMap(Producto::getId, producto -> producto));
        List<Producto> productos = idsProductos.stream().map(porId::get).filter(Objects::nonNull).toList();
        Map<String, String> imagenes = productos.stream().filter(producto -> producto.getImagen() != null)
                .collect(Collectors.toMap(Producto::getId, Producto::getImagen));
        Map<String, String> nombresCategorias = nombresDeCategorias(productos);
        Map<String, Puntuacion> puntuaciones = puntuacionesPorProducto(repositorioValoracion.findAll());
        return productos.stream()
                .map(producto -> aDTO(producto, imagenes, nombresCategorias, clientePremium,
                        puntuaciones.getOrDefault(producto.getId(), SIN_VALORACIONES)))
                .toList();
    }

    public ProductoDetalleDTO obtener(String idProducto, boolean clientePremium) {
        Producto producto = repositorioProducto.findById(idProducto)
                .filter(encontrado -> Boolean.TRUE.equals(encontrado.getVisible()))
                .orElseThrow(ProductoNoDisponibleException::new);
        List<String> categorias = nombresCategorias(producto, nombresDeCategorias(List.of(producto)));
        Puntuacion puntuacion = puntuacionesPorProducto(
                repositorioValoracion.buscarPorProducto(new ObjectId(producto.getId())))
                .getOrDefault(producto.getId(), SIN_VALORACIONES);
        PrecioProducto precio = producto.getPrecio();
        boolean conPrecio = tienePrecio(precio);
        return new ProductoDetalleDTO(producto.getId(), producto.getNombre(), producto.getDescripcion(),
                producto.getImagen(), categorias, conPrecio ? precio.getBase() : null,
                conPrecio ? precio.precioFinal(clientePremium) : null, precio == null ? null : precio.getDescuento(),
                precio == null ? null : precio.getDescuentoPremium(), Boolean.TRUE.equals(producto.getSoloPremium()),
                Disponibilidad.de(producto.getStock()), puntuacion.media(), puntuacion.numero());
    }

    // Las imagenes (base64) son lo que mas ocupa: solo se piden las de los productos de la pagina
    private Map<String, String> imagenesDe(List<Producto> productos) {
        if (productos.isEmpty()) {
            return Map.of();
        }
        List<String> ids = productos.stream().map(Producto::getId).toList();
        return repositorioProducto.findAllById(ids).stream()
                .filter(producto -> producto.getImagen() != null)
                .collect(Collectors.toMap(Producto::getId, Producto::getImagen));
    }

    // Una sola consulta para las categorias de todos los productos de la pagina
    private Map<String, String> nombresDeCategorias(List<Producto> productos) {
        Set<String> ids = productos.stream()
                .map(Producto::getIdCategorias)
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return repositorioCategoria.findAllById(ids).stream()
                .collect(Collectors.toMap(Categoria::getId, Categoria::getNombre));
    }

    // Precio que paga este cliente, dentro del rango pedido (limites incluidos)
    private static boolean cumplePrecio(Producto producto, FiltroCatalogoCliente filtro, boolean clientePremium) {
        if (filtro.precioMinimo() == null && filtro.precioMaximo() == null) {
            return true;
        }
        if (!tienePrecio(producto.getPrecio())) {
            return false;
        }
        double precio = producto.getPrecio().precioFinal(clientePremium);
        return (filtro.precioMinimo() == null || precio >= filtro.precioMinimo())
                && (filtro.precioMaximo() == null || precio <= filtro.precioMaximo());
    }

    private static boolean cumpleValoracion(Puntuacion puntuacion, FiltroCatalogoCliente filtro) {
        return filtro.valoracionMinima() == null
                || (puntuacion != null && puntuacion.media() >= filtro.valoracionMinima());
    }

    // Media y numero de valoraciones de cada producto
    private static Map<String, Puntuacion> puntuacionesPorProducto(Collection<Valoracion> valoraciones) {
        return valoraciones.stream()
                .collect(Collectors.groupingBy(Valoracion::getIdProducto, Collectors.collectingAndThen(
                        Collectors.toList(),
                        lista -> new Puntuacion(lista.stream().mapToInt(Valoracion::getPuntos).average().orElse(0),
                                lista.size()))));
    }

    private static ProductoClienteDTO aDTO(Producto producto, Map<String, String> imagenes,
                                           Map<String, String> nombresCategorias, boolean clientePremium,
                                           Puntuacion puntuacion) {
        PrecioProducto precio = producto.getPrecio();
        boolean conPrecio = tienePrecio(precio);
        return new ProductoClienteDTO(producto.getId(), producto.getNombre(), imagenes.get(producto.getId()),
                nombresCategorias(producto, nombresCategorias), conPrecio ? precio.getBase() : null,
                conPrecio ? precio.precioFinal(clientePremium) : null, Boolean.TRUE.equals(producto.getSoloPremium()),
                Disponibilidad.de(producto.getStock()), puntuacion.media(), puntuacion.numero());
    }

    // Nombres de las categorias del producto (si se ha borrado alguna, no sale)
    private static List<String> nombresCategorias(Producto producto, Map<String, String> nombresCategorias) {
        return producto.getIdCategorias() == null ? List.of()
                : producto.getIdCategorias().stream().map(nombresCategorias::get).filter(Objects::nonNull).toList();
    }

    private static boolean tienePrecio(PrecioProducto precio) {
        return precio != null && precio.getBase() != null;
    }
}