package com.esibuy.esibuy_backend.servicio;

import java.text.Collator;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;


import com.esibuy.esibuy_backend.dto.ProductoAltaDTO;
import com.esibuy.esibuy_backend.dto.ProductoCatalogoDTO;
import com.esibuy.esibuy_backend.excepcion.ProductoNoEncontradoException;
import com.esibuy.esibuy_backend.excepcion.ProductoInvalidoException;
import com.esibuy.esibuy_backend.modelo.Categoria;
import com.esibuy.esibuy_backend.modelo.PrecioProducto;
import com.esibuy.esibuy_backend.modelo.Producto;
import com.esibuy.esibuy_backend.repositorio.RepositorioCategoria;
import com.esibuy.esibuy_backend.repositorio.RepositorioProducto;

/**
 * Productos del vendedor de la sesion: su catalogo propio (listado y detalle). Solo se leen los productos cuyo
 * idVendedor es el del vendedor, asi que nunca se muestran los de otros.
 */
@Service
public class ServicioProductoImpl implements ServicioProducto {

    // HU15.1: valores minimos del alta de producto
    private static final double PRECIO_MINIMO = 0;
    private static final int STOCK_MINIMO = 0;

    private final RepositorioProducto repositorioProducto;
    private final RepositorioCategoria repositorioCategoria;

    public ServicioProductoImpl(RepositorioProducto repositorioProducto, RepositorioCategoria repositorioCategoria) {
        this.repositorioProducto = repositorioProducto;
        this.repositorioCategoria = repositorioCategoria;
    }

    // Ordenados por nombre con las reglas del espanol, igual que el listado de categorias.
    @Override
    public List<ProductoCatalogoDTO> listarProductosDelVendedor(String idVendedor) {
        List<Producto> productos = repositorioProducto.buscarPorVendedor(new ObjectId(idVendedor));
        Map<String, String> nombresCategorias = nombresDeCategorias(productos);
        Collator ordenEspanol = Collator.getInstance(Locale.of("es", "ES"));
        return productos.stream()
                .map(producto -> aDTO(producto, nombresCategorias))
                .sorted(Comparator.comparing(ProductoCatalogoDTO::nombre, ordenEspanol))
                .toList();
    }

    // Un producto de otro vendedor se trata igual que uno inexistente (404).
    @Override
    public ProductoCatalogoDTO obtenerProductoDelVendedor(String idVendedor, String idProducto) {
        Producto producto = repositorioProducto.findById(idProducto)
                .filter(encontrado -> idVendedor.equals(encontrado.getIdVendedor()))
                .orElseThrow(ProductoNoEncontradoException::new);
        return aDTO(producto, nombresDeCategorias(List.of(producto)));
    }

    // HU15.1 - Alta de producto. Si falta un campo obligatorio o es incorrecto: 400 con el motivo y no se guarda.
        @Override
    public ProductoCatalogoDTO crearProducto(String idVendedor, ProductoAltaDTO datos) {
        if (vacio(datos.nombre()) || vacio(datos.descripcion()) || datos.precio() == null
                || datos.stock() == null || datos.idCategorias() == null || datos.idCategorias().isEmpty()) {
            throw new ProductoInvalidoException("Rellena todos los campos obligatorios");
        }
        if (datos.precio() <= PRECIO_MINIMO || datos.stock() < STOCK_MINIMO
                || repositorioCategoria.findAllById(datos.idCategorias()).size() != datos.idCategorias().size()) {
            throw new ProductoInvalidoException(
                    "El precio debe ser mayor que 0, el stock no puede ser negativo y las categorías deben existir");
        }

        Producto producto = Producto.builder()
                .idVendedor(idVendedor)
                .idCategorias(datos.idCategorias())
                .nombre(datos.nombre().strip())
                .descripcion(datos.descripcion().strip())
                .imagen(datos.imagen())
                .precio(new PrecioProducto(datos.precio(), null, null))
                .stock(datos.stock())
                .visible(true)
                .build();
        Producto guardado = repositorioProducto.save(producto);
        return aDTO(guardado, nombresDeCategorias(List.of(guardado)));
    }

    private static boolean vacio(String texto) {
        return texto == null || texto.isBlank();
    }

    // Una sola consulta para las categorias de todos los productos, en vez de una por producto.
    private Map<String, String> nombresDeCategorias(Collection<Producto> productos) {
        Set<String> idsCategorias = productos.stream()
                .map(Producto::getIdCategorias)
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .collect(Collectors.toSet());
        if (idsCategorias.isEmpty()) {
            return Map.of();
        }
        return repositorioCategoria.findAllById(idsCategorias).stream()
                .collect(Collectors.toMap(Categoria::getId, Categoria::getNombre));
    }

    private ProductoCatalogoDTO aDTO(Producto producto, Map<String, String> nombresCategorias) {
        List<String> categorias = producto.getIdCategorias() == null ? List.of()
                : producto.getIdCategorias().stream()
                        .map(nombresCategorias::get)
                        .filter(Objects::nonNull)
                        .toList();
        PrecioProducto precio = producto.getPrecio();
        return new ProductoCatalogoDTO(producto.getId(), producto.getNombre(), producto.getDescripcion(),
                producto.getImagen(), categorias, precio.getBase(), precio.getDescuento(),
                precio.getDescuentoPremium(), producto.getStock(), producto.getVisible());
    }
}
