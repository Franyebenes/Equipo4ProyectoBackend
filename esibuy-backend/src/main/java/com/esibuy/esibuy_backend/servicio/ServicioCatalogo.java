package com.esibuy.esibuy_backend.servicio;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.esibuy.esibuy_backend.dto.Disponibilidad;
import com.esibuy.esibuy_backend.dto.PaginaDTO;
import com.esibuy.esibuy_backend.dto.ProductoClienteDTO;
import com.esibuy.esibuy_backend.modelo.Categoria;
import com.esibuy.esibuy_backend.modelo.PrecioProducto;
import com.esibuy.esibuy_backend.modelo.Producto;
import com.esibuy.esibuy_backend.repositorio.RepositorioCategoria;
import com.esibuy.esibuy_backend.repositorio.RepositorioProducto;

@Service
public class ServicioCatalogo {

    /** Productos por pagina: 3 filas de 4 en la cuadricula del catalogo. */
    public static final int TAMANO_PAGINA = 12;
    private static final int PRIMERA_PAGINA = 0;

    private final RepositorioProducto repositorioProducto;
    private final RepositorioCategoria repositorioCategoria;

    public ServicioCatalogo(RepositorioProducto repositorioProducto, RepositorioCategoria repositorioCategoria) {
        this.repositorioProducto = repositorioProducto;
        this.repositorioCategoria = repositorioCategoria;
    }

    /**
     * Una pagina del catalogo. La busqueda es por nombre completo o parcial, sin distinguir mayusculas ni tildes (la
     * misma que en el catalogo del vendedor); vacia, todo el catalogo. La pagina empieza en 0; una negativa se trata
     * como la primera y una que no existe sale vacia.
     *
     * @param clientePremium si es true, el precio final lleva tambien el descuento premium
     */
    public PaginaDTO<ProductoClienteDTO> buscar(String busqueda, int pagina, boolean clientePremium) {
        FiltroCatalogo filtro = new FiltroCatalogo(busqueda, null, null, null, null);
        // El id de MongoDB empieza por la fecha de creacion: ordenado al reves, los mas nuevos primero
        List<Producto> encontrados = repositorioProducto.buscarActivos().stream()
                .filter(filtro::admite)
                .sorted(Comparator.comparing(Producto::getId).reversed())
                .toList();

        int numeroPagina = Math.max(pagina, PRIMERA_PAGINA);
        long desde = (long) numeroPagina * TAMANO_PAGINA;
        List<Producto> dePagina = encontrados.stream().skip(desde).limit(TAMANO_PAGINA).toList();

        Map<String, String> imagenes = imagenesDe(dePagina);
        Map<String, String> nombresCategorias = nombresDeCategorias(dePagina);
        List<ProductoClienteDTO> contenido = dePagina.stream()
                .map(producto -> aDTO(producto, imagenes, nombresCategorias, clientePremium))
                .toList();
        return PaginaDTO.desde(
                new PageImpl<>(contenido, PageRequest.of(numeroPagina, TAMANO_PAGINA), encontrados.size()));
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

    private static ProductoClienteDTO aDTO(Producto producto, Map<String, String> imagenes,
                                           Map<String, String> nombresCategorias, boolean clientePremium) {
        List<String> categorias = producto.getIdCategorias() == null ? List.of()
                : producto.getIdCategorias().stream().map(nombresCategorias::get).filter(Objects::nonNull).toList();
        PrecioProducto precio = producto.getPrecio();
        boolean conPrecio = precio != null && precio.getBase() != null;
        return new ProductoClienteDTO(producto.getId(), producto.getNombre(), imagenes.get(producto.getId()),
                categorias, conPrecio ? precio.getBase() : null, conPrecio ? precio.precioFinal(clientePremium) : null,
                Boolean.TRUE.equals(producto.getSoloPremium()), Disponibilidad.de(producto.getStock()));
    }
}