package com.esibuy.esibuy_backend.servicio;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import com.esibuy.esibuy_backend.dto.CarritoDTO;
import com.esibuy.esibuy_backend.dto.LineaCarritoDTO;
import com.esibuy.esibuy_backend.excepcion.CantidadNoValidaException;
import com.esibuy.esibuy_backend.excepcion.ProductoNoDisponibleException;
import com.esibuy.esibuy_backend.excepcion.ProductoSoloPremiumException;
import com.esibuy.esibuy_backend.excepcion.ProductoNoEnCarritoException;
import com.esibuy.esibuy_backend.excepcion.StockInsuficienteException;
import com.esibuy.esibuy_backend.modelo.Carrito;
import com.esibuy.esibuy_backend.modelo.LineaCarrito;
import com.esibuy.esibuy_backend.modelo.PrecioProducto;
import com.esibuy.esibuy_backend.modelo.Producto;
import com.esibuy.esibuy_backend.repositorio.RepositorioCarrito;
import com.esibuy.esibuy_backend.repositorio.RepositorioProducto;
import com.mongodb.client.result.UpdateResult;

/**
 * Carrito del cliente (HU-22.x). El stock se reserva al anadir al carrito y se devuelve al quitar el producto.
 */
@Service
public class ServicioCarrito {

    private static final Logger log = LoggerFactory.getLogger(ServicioCarrito.class);
    private static final String CAMPO_STOCK = "stock";
    /** Pasado este tiempo sin tocar el carrito, sus unidades vuelven al stock y el carrito se vacia. */
    public static final Duration CADUCIDAD_CARRITO = Duration.ofHours(24);
    private static final String CAMPO_LINEAS = "lineas";
    private static final String CAMPO_PRIMERA_LINEA = "lineas.0";
    private static final String CAMPO_ULTIMA_MODIFICACION = "ultimaModificacion";

    private final RepositorioCarrito repositorioCarrito;
    private final RepositorioProducto repositorioProducto;
    private final MongoTemplate mongoTemplate;

    public ServicioCarrito(RepositorioCarrito repositorioCarrito, RepositorioProducto repositorioProducto,
                           MongoTemplate mongoTemplate) {
        this.repositorioCarrito = repositorioCarrito;
        this.repositorioProducto = repositorioProducto;
        this.mongoTemplate = mongoTemplate;
    }

    // HU-22.1 Ver carrito: precios actuales del producto, con el descuento que le toca al cliente
    public CarritoDTO obtener(String idCliente, boolean premium) {
        Optional<Carrito> carrito = repositorioCarrito.buscarPorCliente(new ObjectId(idCliente));
        if (carrito.isEmpty() || carrito.get().getLineas().isEmpty()) {
            return CarritoDTO.vacio();
        }
        List<String> ids = carrito.get().getLineas().stream().map(LineaCarrito::getIdProducto).toList();
        Map<String, Producto> productos = new HashMap<>();
        repositorioProducto.findAllById(ids).forEach(p -> productos.put(p.getId(), p));

        List<LineaCarritoDTO> lineas = new ArrayList<>();
        for (LineaCarrito linea : carrito.get().getLineas()) {
            Producto producto = productos.get(linea.getIdProducto());
            // Si el producto se ha borrado de la base de datos, la linea no se muestra
            if (producto != null) {
                lineas.add(aLinea(producto, linea.getCantidad(), premium));
            }
        }
        double total = redondear(lineas.stream()
                .filter(LineaCarritoDTO::disponible).mapToDouble(LineaCarritoDTO::subtotal).sum());
        int articulos = lineas.stream().filter(LineaCarritoDTO::disponible).mapToInt(LineaCarritoDTO::cantidad).sum();
        return new CarritoDTO(lineas, articulos, total);
    }

    // HU-22.2 Anadir al carrito: si ya estaba, suma la cantidad. Un producto solo premium solo lo puede anadir un
    // cliente premium.
    public void anadir(String idCliente, String idProducto, int cantidad, boolean premium) {
        if (cantidad < 1) {
            throw new CantidadNoValidaException();
        }
        Producto producto = buscarDisponible(idProducto);
        comprobarPuedeComprarlo(producto, premium);
        reservarStock(producto.getId(), cantidad);
        Carrito carrito = repositorioCarrito.buscarPorCliente(new ObjectId(idCliente))
                .orElseGet(() -> new Carrito(idCliente));
        carrito.anadir(producto.getId(), cantidad);
        carrito.marcarModificado(Instant.now());
        repositorioCarrito.save(carrito);
        log.info("Producto anadido al carrito: cliente={} producto={} cantidad={}", idCliente, idProducto, cantidad);
    }

    // HU-22.3 Modificar cantidad: si sube se reserva la diferencia; si baja, se devuelve al stock. Subirla tiene las
    // mismas condiciones que anadir.
    public void cambiarCantidad(String idCliente, String idProducto, int cantidad, boolean premium) {
        if (cantidad < 1) {
            throw new CantidadNoValidaException();
        }
        Carrito carrito = buscarCarrito(idCliente);
        LineaCarrito linea = carrito.buscarLinea(idProducto).orElseThrow(ProductoNoEnCarritoException::new);
        int diferencia = cantidad - linea.getCantidad();
        if (diferencia > 0) {
            comprobarPuedeComprarlo(buscarDisponible(idProducto), premium);
            reservarStock(idProducto, diferencia);
        } else if (diferencia < 0) {
            liberarStock(idProducto, -diferencia);
        }
        linea.cambiarCantidad(cantidad);
        carrito.marcarModificado(Instant.now());
        repositorioCarrito.save(carrito);
        log.info("Cantidad cambiada en el carrito: cliente={} producto={} cantidad={}", idCliente, idProducto, cantidad);
    }

    // HU-22.4 Eliminar del carrito: se devuelven al stock todas las unidades reservadas
    public void eliminar(String idCliente, String idProducto) {
        Carrito carrito = buscarCarrito(idCliente);
        LineaCarrito linea = carrito.buscarLinea(idProducto).orElseThrow(ProductoNoEnCarritoException::new);
        liberarStock(idProducto, linea.getCantidad());
        carrito.quitar(idProducto);
        carrito.marcarModificado(Instant.now());
        repositorioCarrito.save(carrito);
        log.info("Producto eliminado del carrito: cliente={} producto={}", idCliente, idProducto);
    }

    /**
     * Carritos abandonados: los que llevan mas de 24 horas sin tocarse se vacian y sus unidades vuelven al stock. Lo
     * llama cada hora la tarea programada (y nada mas arrancar, para los que caducaron con el servidor apagado).
     *
     * @return cuantos carritos se han vaciado
     */
    public int liberarCarritosCaducados(Instant ahora) {
        // Carritos de antes de que existiera la caducidad (sin fecha): empiezan a contar desde ahora
        mongoTemplate.updateMulti(new Query(Criteria.where(CAMPO_ULTIMA_MODIFICACION).exists(false)),
                new Update().set(CAMPO_ULTIMA_MODIFICACION, ahora), Carrito.class);

        Query caducados = new Query(Criteria.where(CAMPO_ULTIMA_MODIFICACION).lt(ahora.minus(CADUCIDAD_CARRITO))
                .and(CAMPO_PRIMERA_LINEA).exists(true));
        Update vaciar = new Update().set(CAMPO_LINEAS, List.of()).set(CAMPO_ULTIMA_MODIFICACION, ahora);
        int vaciados = 0;
        // Cada carrito se vacia y se lee (como estaba) en una sola operacion: aunque el cliente lo toque a la vez,
        // sus unidades nunca se devuelven dos veces
        Carrito carrito = mongoTemplate.findAndModify(caducados, vaciar, Carrito.class);
        while (carrito != null) {
            carrito.getLineas().forEach(linea -> liberarStock(linea.getIdProducto(), linea.getCantidad()));
            log.info("Carrito caducado vaciado: cliente={} lineas={}", carrito.getIdCliente(), carrito.getLineas().size());
            vaciados++;
            carrito = mongoTemplate.findAndModify(caducados, vaciar, Carrito.class);
        }
        return vaciados;
    }

    /**
     * Precio por unidad con descuento: el mismo calculo que en el catalogo (PrecioProducto#precioFinal). Un cliente
     * normal tiene el descuento general; un premium, ademas, el premium sobre ese precio (los dos encadenados).
     */
    static double precioFinal(PrecioProducto precio, boolean premium) {
        if (precio == null || precio.getBase() == null) {
            return 0.0;
        }
        return precio.precioFinal(premium);
    }

    private static void comprobarPuedeComprarlo(Producto producto, boolean premium) {
        if (Boolean.TRUE.equals(producto.getSoloPremium()) && !premium) {
            throw new ProductoSoloPremiumException();
        }
    }

    private Carrito buscarCarrito(String idCliente) {
        return repositorioCarrito.buscarPorCliente(new ObjectId(idCliente))
                .orElseThrow(ProductoNoEnCarritoException::new);
    }

    private LineaCarritoDTO aLinea(Producto producto, int cantidad, boolean premium) {
        PrecioProducto precio = producto.getPrecio();
        double original = precio == null ? 0.0 : valor(precio.getBase());
        double unitario = precioFinal(precio, premium);
        boolean disponible = Boolean.TRUE.equals(producto.getVisible());
        return new LineaCarritoDTO(producto.getId(), producto.getNombre(), producto.getImagen(), unitario, original,
                cantidad, redondear(unitario * cantidad), disponible);
    }

    private Producto buscarDisponible(String idProducto) {
        if (idProducto == null || !ObjectId.isValid(idProducto)) {
            throw new ProductoNoDisponibleException();
        }
        return repositorioProducto.findById(idProducto)
                .filter(p -> Boolean.TRUE.equals(p.getVisible()))
                .orElseThrow(ProductoNoDisponibleException::new);
    }

    /**
     * Resta las unidades del stock en una sola operacion de MongoDB y solo si hay suficientes. Asi dos clientes no
     * pueden reservar la misma unidad a la vez.
     */
    private void reservarStock(String idProducto, int cantidad) {
        Query consulta = new Query(Criteria.where("_id").is(new ObjectId(idProducto)).and(CAMPO_STOCK).gte(cantidad));
        UpdateResult resultado = mongoTemplate.updateFirst(consulta, new Update().inc(CAMPO_STOCK, -cantidad), Producto.class);
        if (resultado.getModifiedCount() == 0) {
            throw new StockInsuficienteException();
        }
    }

    /** Devuelve unidades al stock. Si el producto ya no existe, no hay nada que devolver. */
    private void liberarStock(String idProducto, int cantidad) {
        Query consulta = new Query(Criteria.where("_id").is(new ObjectId(idProducto)));
        mongoTemplate.updateFirst(consulta, new Update().inc(CAMPO_STOCK, cantidad), Producto.class);
    }

    private static double valor(Double numero) {
        return numero == null ? 0.0 : numero;
    }

    private static double redondear(double importe) {
        return Math.round(importe * 100) / 100.0;
    }
}