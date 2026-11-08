package com.esibuy.esibuy_backend.servicio;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import com.esibuy.esibuy_backend.dto.ListaDTO;
import com.esibuy.esibuy_backend.dto.ListaDetalleDTO;
import com.esibuy.esibuy_backend.dto.ProductoEnListaDTO;
import com.esibuy.esibuy_backend.excepcion.ConflictoNegocioException;
import com.esibuy.esibuy_backend.excepcion.RecursoNoEncontradoException;
import com.esibuy.esibuy_backend.modelo.Favorito;
import com.esibuy.esibuy_backend.modelo.ListaProductos;
import com.esibuy.esibuy_backend.modelo.Producto;
import com.esibuy.esibuy_backend.repositorio.RepositorioFavorito;
import com.esibuy.esibuy_backend.repositorio.RepositorioListaProductos;

/** Listas de productos del cliente, con Favoritos como lista por defecto (sustituye a HU-31.x). */
@Service
public class ServicioListas {

    static final int MAXIMO_LISTAS = 20;
    static final int MAXIMO_PRODUCTOS = 200;
    /** Imagenes del mosaico de cada coleccion en "Mis listas". */
    static final int MAXIMO_PORTADAS = 4;
    static final String MENSAJE_LISTA_NO_ENCONTRADA = "La lista no existe";
    static final String MENSAJE_PRODUCTO_NO_ENCONTRADO = "El producto no existe";
    static final String MENSAJE_NOMBRE_DUPLICADO = "Ya tienes una lista con ese nombre";
    static final String CODIGO_NOMBRE_DUPLICADO = "NOMBRE_LISTA_DUPLICADO";
    static final String CODIGO_LISTA_POR_DEFECTO = "LISTA_POR_DEFECTO";
    static final String CODIGO_LIMITE_LISTAS = "LIMITE_LISTAS";
    static final String CODIGO_LIMITE_PRODUCTOS = "LIMITE_PRODUCTOS";

    private static final Logger log = LoggerFactory.getLogger(ServicioListas.class);

    private final RepositorioListaProductos repositorio;
    private final RepositorioFavorito repositorioFavorito;
    private final MongoTemplate mongoTemplate;

    public ServicioListas(RepositorioListaProductos repositorio, RepositorioFavorito repositorioFavorito,
                          MongoTemplate mongoTemplate) {
        this.repositorio = repositorio;
        this.repositorioFavorito = repositorioFavorito;
        this.mongoTemplate = mongoTemplate;
    }

    public List<ListaDTO> listar(String idUsuario) {
        asegurarFavoritos(idUsuario);
        List<ListaProductos> listas = repositorio.buscarPorUsuario(new ObjectId(idUsuario));
        Map<String, String> imagenes = imagenesDe(listas.stream()
                .flatMap(lista -> ultimosGuardados(lista).stream()).distinct().toList());
        return listas.stream()
                .map(lista -> ListaDTO.desde(lista, ultimosGuardados(lista).stream()
                        .map(imagenes::get).filter(Objects::nonNull).limit(MAXIMO_PORTADAS).toList()))
                .toList();
    }

    public ListaDetalleDTO obtener(String idUsuario, String idLista, boolean premium) {
        ListaProductos lista = buscarPropia(idUsuario, idLista);
        Map<String, Producto> productos = productosDe(lista);
        // Los ultimos guardados primero, como en una coleccion; los productos borrados de la plataforma no aparecen
        List<ProductoEnListaDTO> filas = lista.getIdProductos().reversed().stream()
                .map(productos::get)
                .filter(Objects::nonNull)
                .map(p -> new ProductoEnListaDTO(p.getId(), p.getNombre(), p.getImagen(),
                        p.getPrecio() == null ? null : p.getPrecio().getBase(), disponiblePara(p, premium)))
                .toList();
        return new ListaDetalleDTO(lista.getId(), lista.getNombre(), lista.isPorDefecto(), filas);
    }

    public ListaDTO crear(String idUsuario, String nombre) {
        // Primero Favoritos: si no, una lista propia llamada "Favoritos" ocuparia su nombre
        asegurarFavoritos(idUsuario);
        ObjectId usuario = new ObjectId(idUsuario);
        if (repositorio.contarPorUsuario(usuario) >= MAXIMO_LISTAS) {
            throw new ConflictoNegocioException(CODIGO_LIMITE_LISTAS,
                    "Has alcanzado el maximo de " + MAXIMO_LISTAS + " listas");
        }
        String limpio = normalizar(nombre);
        comprobarNombreLibre(usuario, limpio);
        return ListaDTO.desde(guardar(ListaProductos.nueva(idUsuario, limpio)));
    }

    public ListaDTO renombrar(String idUsuario, String idLista, String nombre) {
        ListaProductos lista = buscarPropia(idUsuario, idLista);
        rechazarSiEsPorDefecto(lista);
        String limpio = normalizar(nombre);
        if (!limpio.equalsIgnoreCase(lista.getNombre())) {
            comprobarNombreLibre(new ObjectId(idUsuario), limpio);
        }
        lista.renombrar(limpio);
        return ListaDTO.desde(guardar(lista));
    }

    public void eliminar(String idUsuario, String idLista) {
        ListaProductos lista = buscarPropia(idUsuario, idLista);
        rechazarSiEsPorDefecto(lista);
        repositorio.delete(lista);
    }

    /** Idempotente: si el producto ya estaba, no hace nada y responde igual. */
    public ListaDTO anadirProducto(String idUsuario, String idLista, String idProducto, boolean premium) {
        ListaProductos lista = buscarPropia(idUsuario, idLista);
        if (!productoDisponible(idProducto, premium)) {
            throw new RecursoNoEncontradoException(MENSAJE_PRODUCTO_NO_ENCONTRADO);
        }
        if (lista.contiene(idProducto)) {
            return ListaDTO.desde(lista);
        }
        if (lista.getIdProductos().size() >= MAXIMO_PRODUCTOS) {
            throw new ConflictoNegocioException(CODIGO_LIMITE_PRODUCTOS,
                    "La lista ya tiene el maximo de " + MAXIMO_PRODUCTOS + " productos");
        }
        lista.anadirProducto(idProducto);
        return ListaDTO.desde(repositorio.save(lista));
    }

    /** No comprueba el producto: asi se puede quitar uno desactivado o borrado. */
    public ListaDTO quitarProducto(String idUsuario, String idLista, String idProducto) {
        ListaProductos lista = buscarPropia(idUsuario, idLista);
        if (lista.quitarProducto(idProducto)) {
            lista = repositorio.save(lista);
        }
        return ListaDTO.desde(lista);
    }

    /**
     * La lista Favoritos del cliente, la misma que usan el corazon y la pestana Favoritos (ServicioFavoritos). Se crea
     * si no existe y se le pasan los favoritos que quedaran en la coleccion antigua "favoritos".
     */
    public ListaProductos favoritos(String idUsuario) {
        asegurarFavoritos(idUsuario);
        return repositorio.buscarFavoritos(new ObjectId(idUsuario))
                .orElseThrow(() -> new IllegalStateException("La lista Favoritos deberia existir"));
    }

    private void asegurarFavoritos(String idUsuario) {
        ObjectId usuario = new ObjectId(idUsuario);
        if (!repositorio.existeFavoritos(usuario)) {
            try {
                repositorio.save(ListaProductos.favoritos(idUsuario));
            } catch (DuplicateKeyException e) {
                // Otra peticion la ha creado a la vez: el indice unico parcial lo impide y no pasa nada
            }
        }
        migrarFavoritosAntiguos(usuario);
    }

    /**
     * Antes los favoritos se guardaban en la coleccion "favoritos", un documento por producto. Se pasan a la lista
     * Favoritos en el orden en que se anadieron y se borran de alli, asi que esto solo hace algo la primera vez.
     */
    private void migrarFavoritosAntiguos(ObjectId usuario) {
        List<Favorito> antiguos = repositorioFavorito.buscarPorCliente(usuario);
        if (antiguos.isEmpty()) {
            return;
        }
        repositorio.buscarFavoritos(usuario).ifPresent(lista -> {
            antiguos.stream()
                    .sorted(Comparator.comparing(Favorito::getAnadidoEn,
                            Comparator.nullsFirst(Comparator.<Instant>naturalOrder())))
                    .forEach(favorito -> lista.anadirProducto(favorito.getIdProducto()));
            repositorio.save(lista);
            repositorioFavorito.deleteAll(antiguos);
            log.info("Favoritos antiguos pasados a la lista Favoritos: usuario={} productos={}", usuario,
                    antiguos.size());
        });
    }

    private ListaProductos buscarPropia(String idUsuario, String idLista) {
        if (idLista == null || !ObjectId.isValid(idLista)) {
            throw new RecursoNoEncontradoException(MENSAJE_LISTA_NO_ENCONTRADA);
        }
        return repositorio.buscarPorIdYUsuario(new ObjectId(idLista), new ObjectId(idUsuario))
                .orElseThrow(() -> new RecursoNoEncontradoException(MENSAJE_LISTA_NO_ENCONTRADA));
    }

    private void comprobarNombreLibre(ObjectId usuario, String nombre) {
        if (repositorio.existePorUsuarioYNombre(usuario, nombre)) {
            throw new ConflictoNegocioException(CODIGO_NOMBRE_DUPLICADO, MENSAJE_NOMBRE_DUPLICADO);
        }
    }

    private static void rechazarSiEsPorDefecto(ListaProductos lista) {
        if (lista.isPorDefecto()) {
            throw new ConflictoNegocioException(CODIGO_LISTA_POR_DEFECTO,
                    "La lista Favoritos no se puede renombrar ni eliminar");
        }
    }

    /** El indice unico es la ultima barrera si dos peticiones usan el mismo nombre a la vez. */
    private ListaProductos guardar(ListaProductos lista) {
        try {
            return repositorio.save(lista);
        } catch (DuplicateKeyException e) {
            throw new ConflictoNegocioException(CODIGO_NOMBRE_DUPLICADO, MENSAJE_NOMBRE_DUPLICADO);
        }
    }

    /** Visible y, si el cliente no es Premium, que no sea solo para Premium. */
    private boolean productoDisponible(String idProducto, boolean premium) {
        if (idProducto == null || !ObjectId.isValid(idProducto)) {
            return false;
        }
        Criteria criterio = Criteria.where("_id").is(new ObjectId(idProducto)).and("visible").is(true);
        if (!premium) {
            criterio = criterio.and("soloPremium").ne(true); // los productos antiguos no tienen el campo
        }
        return mongoTemplate.exists(Query.query(criterio), Producto.class);
    }

    private static boolean disponiblePara(Producto producto, boolean premium) {
        return Boolean.TRUE.equals(producto.getVisible()) && (premium || !Boolean.TRUE.equals(producto.getSoloPremium()));
    }

    /** Una sola consulta para todos los productos de la lista. */
    private Map<String, Producto> productosDe(ListaProductos lista) {
        if (lista.getIdProductos().isEmpty()) {
            return Map.of();
        }
        List<ObjectId> ids = lista.getIdProductos().stream().map(ObjectId::new).toList();
        return mongoTemplate.find(Query.query(Criteria.where("_id").in(ids)), Producto.class).stream()
                .collect(Collectors.toMap(Producto::getId, Function.identity()));
    }

    /**
     * Candidatos a portada: los ultimos productos guardados, del mas reciente al mas antiguo. Se cogen algunos de mas
     * por si los ultimos no tienen imagen o se han borrado.
     */
    private static List<String> ultimosGuardados(ListaProductos lista) {
        return lista.getIdProductos().reversed().stream().limit(MAXIMO_PORTADAS * 2L).toList();
    }

    /** Imagenes de los productos indicados que tienen una, en una sola consulta para todas las listas. */
    private Map<String, String> imagenesDe(List<String> idProductos) {
        if (idProductos.isEmpty()) {
            return Map.of();
        }
        Query consulta = Query.query(Criteria.where("_id").in(idProductos.stream().map(ObjectId::new).toList())
                .and("imagen").ne(null));
        consulta.fields().include("imagen");
        return mongoTemplate.find(consulta, Producto.class).stream()
                .collect(Collectors.toMap(Producto::getId, Producto::getImagen));
    }

    private static String normalizar(String nombre) {
        return nombre.strip().replaceAll("\\s+", " ");
    }
}