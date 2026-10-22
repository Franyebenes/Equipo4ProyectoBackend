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
import java.util.regex.Pattern;

import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;


import com.esibuy.esibuy_backend.dto.ProductoAltaDTO;
import com.esibuy.esibuy_backend.dto.ProductoCatalogoDTO;
import com.esibuy.esibuy_backend.excepcion.ProductoNoEncontradoException;
import com.esibuy.esibuy_backend.excepcion.ProductoInvalidoException;
import com.esibuy.esibuy_backend.excepcion.StockModificadoException;
import com.esibuy.esibuy_backend.modelo.Categoria;
import com.esibuy.esibuy_backend.modelo.PrecioProducto;
import com.esibuy.esibuy_backend.modelo.Producto;
import com.esibuy.esibuy_backend.repositorio.RepositorioCategoria;
import com.esibuy.esibuy_backend.repositorio.RepositorioProducto;
import com.esibuy.esibuy_backend.util.Constantes;

/**
 * Productos del vendedor de la sesion: su catalogo propio (listado y detalle). Solo se leen los productos cuyo
 * idVendedor es el del vendedor, asi que nunca se muestran los de otros.
 */
@Service
public class ServicioProductoImpl implements ServicioProducto {

    // HU15.1: valores minimos del alta de producto
    private static final double PRECIO_MINIMO = 0;
    private static final int STOCK_MINIMO = 0;
    // Descuento en porcentaje, con el mismo rango que el esquema de la BBDD (precio.descuento)
    private static final double DESCUENTO_MINIMO = 0;
    private static final double DESCUENTO_MAXIMO = 100;
    static final String MENSAJE_CAMPOS_OBLIGATORIOS = "Rellena todos los campos obligatorios";
    static final String MENSAJE_VALORES_INCORRECTOS =
            "El precio debe ser mayor que 0, el stock no puede ser negativo y las categorías deben existir";
    static final String MENSAJE_DESCUENTO_INVALIDO = "El descuento debe estar entre 0 y 100 %";
    static final String MENSAJE_ESTADO_OBLIGATORIO = "Indica si el producto queda activo o inactivo";
    static final String MENSAJE_STOCK_OBLIGATORIO = "Indica la cantidad de stock";
    static final String MENSAJE_STOCK_NEGATIVO = "El stock debe ser un número entero no negativo";
    // Limites que tambien comprueba el formulario del frontend
    static final int STOCK_MAXIMO = 1_000_000;
    static final String MENSAJE_STOCK_MAXIMO = "El stock no puede superar 1.000.000 unidades";
    static final int LONGITUD_MAXIMA_NOMBRE = 100;
    static final int LONGITUD_MAXIMA_DESCRIPCION = 2000;
    static final String MENSAJE_NOMBRE_LARGO = "El nombre no puede superar los 100 caracteres";
    static final String MENSAJE_DESCRIPCION_LARGA = "La descripción no puede superar los 2000 caracteres";
    // La imagen es una data URL en base64 (la genera el formulario): JPG, PNG o WEBP de 1 MB como maximo
    static final String MENSAJE_IMAGEN_INVALIDA = "La imagen debe ser JPG, PNG o WEBP y ocupar como máximo 1 MB";
    private static final int TAMANO_MAXIMO_IMAGEN_BYTES = 1024 * 1024;
    private static final List<String> PREFIJOS_IMAGEN =
            List.of("data:image/jpeg;base64,", "data:image/png;base64,", "data:image/webp;base64,");
    private static final Pattern BASE64 = Pattern.compile("[A-Za-z0-9+/]*={0,2}");
    
    // Sin caracteres especiales en el nombre ni en la descripcion (mismas reglas que las categorias)
    private static final Pattern NOMBRE_PERMITIDO = Pattern.compile(Constantes.PATRON_NOMBRE);
    private static final Pattern DESCRIPCION_PERMITIDA = Pattern.compile(Constantes.PATRON_DESCRIPCION);
    
    private final RepositorioProducto repositorioProducto;
    private final RepositorioCategoria repositorioCategoria;

    public ServicioProductoImpl(RepositorioProducto repositorioProducto, RepositorioCategoria repositorioCategoria) {
        this.repositorioProducto = repositorioProducto;
        this.repositorioCategoria = repositorioCategoria;
    }

    // Solo los productos del vendedor que cumplen el filtro, ordenados por nombre con las reglas del espanol, igual
    // que el listado de categorias. El filtro se aplica sobre su catalogo (consulta por idVendedor, sin imagenes).
    @Override
    public List<ProductoCatalogoDTO> listarProductosDelVendedor(String idVendedor, FiltroCatalogo filtro) {
        List<Producto> productos = repositorioProducto.buscarPorVendedor(new ObjectId(idVendedor)).stream()
                .filter(filtro::admite)
                .toList();
        Map<String, String> nombresCategorias = nombresDeCategorias(productos);
        Collator ordenEspanol = Collator.getInstance(Locale.of("es", "ES"));
        return productos.stream()
                .map(producto -> aDTO(producto, nombresCategorias))
                .sorted(Comparator.comparing(ProductoCatalogoDTO::nombre, ordenEspanol))
                .toList();
    }

    @Override
    public ProductoCatalogoDTO obtenerProductoDelVendedor(String idVendedor, String idProducto) {
        Producto producto = productoDelVendedor(idVendedor, idProducto);
        return aDTO(producto, nombresDeCategorias(List.of(producto)));
    }

    // Activar/Desactivar producto: cambia su visibilidad para los clientes sin borrarlo.
    @Override
    public ProductoCatalogoDTO cambiarEstado(String idVendedor, String idProducto, Boolean activo) {
        if (activo == null) {
            throw new ProductoInvalidoException(MENSAJE_ESTADO_OBLIGATORIO);
        }
        Producto producto = productoDelVendedor(idVendedor, idProducto);
        producto.cambiarVisibilidad(activo);
        return guardar(producto);
    }

    // Gestion de stock: el vendedor fija las unidades disponibles (las aumenta o las disminuye). Con 0 el producto
    // deja de estar disponible para la venta. stockAnterior es la cantidad que vio el vendedor (ver stockAGuardar).
    @Override
    public ProductoCatalogoDTO cambiarStock(String idVendedor, String idProducto, Integer stock,
                                            Integer stockAnterior) {
        if (stock == null) {
            throw new ProductoInvalidoException(MENSAJE_STOCK_OBLIGATORIO);
        }
        if (stock < STOCK_MINIMO) {
            throw new ProductoInvalidoException(MENSAJE_STOCK_NEGATIVO);
        }
        if (stock > STOCK_MAXIMO) {
            throw new ProductoInvalidoException(MENSAJE_STOCK_MAXIMO);
        }
        Producto producto = productoDelVendedor(idVendedor, idProducto);
        producto.cambiarStock(stockAGuardar(producto, stock, stockAnterior));
        return guardar(producto);
    }

    // Eliminar producto: lo borra definitivamente. Si estaba en el carrito de algun cliente, el carrito deja de
    // mostrar esa linea (ServicioCarrito ignora los productos que ya no existen).
    @Override
    public void eliminarProducto(String idVendedor, String idProducto) {
        Producto producto = productoDelVendedor(idVendedor, idProducto);
        repositorioProducto.deleteById(producto.getId());
    }

    // Guarda el producto y lo devuelve como en el listado, con sus categorias por nombre
    private ProductoCatalogoDTO guardar(Producto producto) {
        Producto guardado = repositorioProducto.save(producto);
        return aDTO(guardado, nombresDeCategorias(List.of(guardado)));
    }

    // Producto del catalogo del vendedor. Uno de otro vendedor se trata igual que uno inexistente (404), para no
    // revelar que existe.
    private Producto productoDelVendedor(String idVendedor, String idProducto) {
        return repositorioProducto.findById(idProducto)
                .filter(encontrado -> idVendedor.equals(encontrado.getIdVendedor()))
                .orElseThrow(ProductoNoEncontradoException::new);
    }

    // HU15.1 - Alta de producto. Si falta un campo obligatorio o es incorrecto: 400 con el motivo y no se guarda.
    // El vendedor elige si el producto queda activo y, opcionalmente, un descuento (porcentaje sobre el precio).
    @Override
    public ProductoCatalogoDTO crearProducto(String idVendedor, ProductoAltaDTO datos) {
        validarDatosProducto(datos);

        Producto producto = Producto.builder()
                .idVendedor(idVendedor)
                .idCategorias(datos.idCategorias())
                .nombre(datos.nombre().strip())
                .descripcion(datos.descripcion().strip())
                .imagen(datos.imagen())
                .precio(new PrecioProducto(datos.precio(), datos.descuento(), null))
                .stock(datos.stock())
                // Si el vendedor no lo indica, el producto se crea activo (visible para los clientes)
                .visible(!Boolean.FALSE.equals(datos.activo()))
                .build();
        return guardar(producto);
    }

    // Modificar producto: el vendedor cambia los datos de uno de sus productos (tambien el stock y el estado) con las
    // mismas validaciones que el alta. Si algo no es valido: 400 con el motivo y no se guarda. Sin imagen el producto
    // queda sin foto (se muestra la imagen por defecto) y sin activo conserva su estado. stockAnterior es la cantidad
    // que vio el vendedor al abrir el formulario (ver stockAGuardar).
    @Override
    public ProductoCatalogoDTO modificarProducto(String idVendedor, String idProducto, ProductoAltaDTO datos,
                                                 Integer stockAnterior) {
        validarDatosProducto(datos);
        Producto producto = productoDelVendedor(idVendedor, idProducto);
        int stock = stockAGuardar(producto, datos.stock(), stockAnterior);
        producto.modificarDatos(datos.nombre().strip(), datos.descripcion().strip(), datos.idCategorias(),
                datos.imagen(), datos.precio(), datos.descuento());
        producto.cambiarStock(stock);
        if (datos.activo() != null) {
            producto.cambiarVisibilidad(datos.activo());
        }
        return guardar(producto);
    }

    // Cantidad que se guarda. stockAnterior es la que vio el vendedor al abrir la pantalla. Si mientras tanto ha
    // cambiado (p. ej. un cliente ha reservado unidades en su carrito), no se pisa la actual con una antigua:
    //  - si el vendedor no ha tocado el stock, se conserva el actual;
    //  - si lo ha cambiado, 409 con la cantidad actual para que la revise.
    // Sin stockAnterior se guarda la cantidad indicada.
    private static int stockAGuardar(Producto producto, int stockNuevo, Integer stockAnterior) {
        int stockActual = Objects.requireNonNullElse(producto.getStock(), STOCK_MINIMO);
        if (stockAnterior == null || stockAnterior == stockActual) {
            return stockNuevo;
        }
        if (stockAnterior == stockNuevo) {
            return stockActual;
        }
        throw new StockModificadoException(stockActual);
    }

    // Validaciones comunes del alta y de la modificacion: campos obligatorios, nombre y descripcion sin caracteres
    // especiales ni demasiado largos, descuento entre 0 y 100 %, imagen JPG/PNG/WEBP de 1 MB como maximo, precio
    // mayor que 0, stock entre 0 y 1.000.000 y categorias existentes.
    private void validarDatosProducto(ProductoAltaDTO datos) {
        if (vacio(datos.nombre()) || vacio(datos.descripcion()) || datos.precio() == null
                || datos.stock() == null || datos.idCategorias() == null || datos.idCategorias().isEmpty()) {
            throw new ProductoInvalidoException(MENSAJE_CAMPOS_OBLIGATORIOS);
        }
        validarNombreYDescripcion(datos.nombre().strip(), datos.descripcion().strip());
        if (datos.descuento() != null
                && (datos.descuento() < DESCUENTO_MINIMO || datos.descuento() > DESCUENTO_MAXIMO)) {
            throw new ProductoInvalidoException(MENSAJE_DESCUENTO_INVALIDO);
        }
        if (datos.imagen() != null && !imagenValida(datos.imagen())) {
            throw new ProductoInvalidoException(MENSAJE_IMAGEN_INVALIDA);
        }
        if (datos.stock() > STOCK_MAXIMO) {
            throw new ProductoInvalidoException(MENSAJE_STOCK_MAXIMO);
        }
        if (datos.precio() <= PRECIO_MINIMO || datos.stock() < STOCK_MINIMO
                || repositorioCategoria.findAllById(datos.idCategorias()).size() != datos.idCategorias().size()) {
            throw new ProductoInvalidoException(MENSAJE_VALORES_INCORRECTOS);
        }
    }

    private static void validarNombreYDescripcion(String nombre, String descripcion) {
        if (nombre.length() > LONGITUD_MAXIMA_NOMBRE) {
            throw new ProductoInvalidoException(MENSAJE_NOMBRE_LARGO);
        }
        if (!NOMBRE_PERMITIDO.matcher(nombre).matches()) {
            throw new ProductoInvalidoException(Constantes.MENSAJE_NOMBRE_NO_PERMITIDO);
        }
        if (descripcion.length() > LONGITUD_MAXIMA_DESCRIPCION) {
            throw new ProductoInvalidoException(MENSAJE_DESCRIPCION_LARGA);
        }
        if (!DESCRIPCION_PERMITIDA.matcher(descripcion).matches()) {
            throw new ProductoInvalidoException(Constantes.MENSAJE_DESCRIPCION_NO_PERMITIDA);
        }
    }

    // data URL de un tipo admitido, con base64 bien formado y de 1 MB como maximo una vez decodificada. El tamano se
    // calcula sin decodificarla: cada 4 caracteres son 3 bytes, menos el relleno (=) del final.
    private static boolean imagenValida(String imagen) {
        String base64 = PREFIJOS_IMAGEN.stream()
                .filter(imagen::startsWith)
                .findFirst()
                .map(prefijo -> imagen.substring(prefijo.length()))
                .orElse(null);
        if (base64 == null || base64.isEmpty() || base64.length() % 4 != 0 || !BASE64.matcher(base64).matches()) {
            return false;
        }
        long relleno = base64.chars().filter(caracter -> caracter == '=').count();
        return base64.length() / 4L * 3 - relleno <= TAMANO_MAXIMO_IMAGEN_BYTES;
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
