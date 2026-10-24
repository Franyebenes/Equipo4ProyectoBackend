package com.esibuy.esibuy_backend.servicio;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import com.esibuy.esibuy_backend.dto.DireccionEnvioDTO;
import com.esibuy.esibuy_backend.dto.HistorialPedidosDTO;
import com.esibuy.esibuy_backend.dto.LineaPedidoDTO;
import com.esibuy.esibuy_backend.dto.PedidoDTO;
import com.esibuy.esibuy_backend.excepcion.EstadoPedidoNoPermitidoException;
import com.esibuy.esibuy_backend.excepcion.PedidoInvalidoException;
import com.esibuy.esibuy_backend.excepcion.PedidoNoEncontradoException;
import com.esibuy.esibuy_backend.modelo.Carrito;
import com.esibuy.esibuy_backend.modelo.DireccionEnvio;
import com.esibuy.esibuy_backend.modelo.LineaCarrito;
import com.esibuy.esibuy_backend.modelo.LineaPedido;
import com.esibuy.esibuy_backend.modelo.Pedido;
import com.esibuy.esibuy_backend.modelo.Producto;
import com.esibuy.esibuy_backend.modelo.Valoracion;
import com.esibuy.esibuy_backend.repositorio.RepositorioCarrito;
import com.esibuy.esibuy_backend.repositorio.RepositorioPedido;
import com.esibuy.esibuy_backend.repositorio.RepositorioProducto;
import com.esibuy.esibuy_backend.repositorio.RepositorioValoracion;

/**
 * Pedidos del cliente de la sesion (HU-26, HU-27, HU-28 y HU-30). El pago es simulado: realizar el pedido lo deja
 * directamente PAGADO. El stock no se toca al realizarlo porque ya quedo reservado en el carrito; al cancelar se
 * devuelve.
 */
@Service
public class ServicioPedido {

    static final String MENSAJE_CARRITO_VACIO = "Tu carrito está vacío";
    static final String MENSAJE_PRODUCTOS_NO_DISPONIBLES =
            "Quita del carrito los productos que ya no están disponibles antes de tramitar el pedido";
    static final String MENSAJE_DIRECCION_INCOMPLETA = "Indica la calle, la ciudad y el código postal de envío";
    static final String MENSAJE_CODIGO_POSTAL_INVALIDO = "El código postal debe tener 5 números";
    static final String MENSAJE_DIRECCION_LARGA = "La dirección es demasiado larga";
    static final String MENSAJE_NO_CANCELABLE = "Solo se puede cancelar un pedido pagado que aún no se ha enviado";
    static final String MENSAJE_NO_CONFIRMABLE = "Este pedido ya está finalizado o cancelado";

    private static final int LONGITUD_MAXIMA_CALLE = 150;
    private static final int LONGITUD_MAXIMA_CIUDAD = 80;
    private static final Pattern CODIGO_POSTAL = Pattern.compile("\\d{5}");
    private static final String CAMPO_STOCK = "stock";

    private static final Logger log = LoggerFactory.getLogger(ServicioPedido.class);

    private final RepositorioPedido repositorioPedido;
    private final RepositorioCarrito repositorioCarrito;
    private final RepositorioProducto repositorioProducto;
    private final RepositorioValoracion repositorioValoracion;
    private final MongoTemplate mongoTemplate;
    private final Clock reloj;

    public ServicioPedido(RepositorioPedido repositorioPedido, RepositorioCarrito repositorioCarrito,
                          RepositorioProducto repositorioProducto, RepositorioValoracion repositorioValoracion,
                          MongoTemplate mongoTemplate, Clock reloj) {
        this.repositorioPedido = repositorioPedido;
        this.repositorioCarrito = repositorioCarrito;
        this.repositorioProducto = repositorioProducto;
        this.repositorioValoracion = repositorioValoracion;
        this.mongoTemplate = mongoTemplate;
        this.reloj = reloj;
    }

    // HU-26 Realizar pedido: convierte el carrito en un pedido pagado con los precios de este momento (los mismos
    // que ve el cliente en el carrito) y vacia el carrito. Si algun producto del carrito esta desactivado, el
    // cliente tiene que quitarlo antes.
    public PedidoDTO realizar(String idCliente, boolean premium, DireccionEnvioDTO direccion) {
        DireccionEnvio direccionEnvio = validarDireccion(direccion);
        Carrito carrito = repositorioCarrito.buscarPorCliente(new ObjectId(idCliente))
                .orElseThrow(() -> new PedidoInvalidoException(MENSAJE_CARRITO_VACIO));

        List<String> ids = carrito.getLineas().stream().map(LineaCarrito::getIdProducto).toList();
        Map<String, Producto> productos = new HashMap<>();
        repositorioProducto.findAllById(ids).forEach(p -> productos.put(p.getId(), p));

        List<LineaPedido> lineas = new ArrayList<>();
        for (LineaCarrito linea : carrito.getLineas()) {
            Producto producto = productos.get(linea.getIdProducto());
            // Un producto borrado de la base de datos tampoco aparece en el carrito: se ignora, igual que alli
            if (producto == null) {
                continue;
            }
            if (!Boolean.TRUE.equals(producto.getVisible())) {
                throw new PedidoInvalidoException(MENSAJE_PRODUCTOS_NO_DISPONIBLES);
            }
            lineas.add(new LineaPedido(producto.getId(), producto.getNombre(), linea.getCantidad(),
                    ServicioCarrito.precioFinal(producto.getPrecio(), premium)));
        }
        if (lineas.isEmpty()) {
            throw new PedidoInvalidoException(MENSAJE_CARRITO_VACIO);
        }
        double total = redondear(lineas.stream().mapToDouble(l -> l.getPrecioUnitario() * l.getCantidad()).sum());

        Pedido pedido = repositorioPedido.save(new Pedido(idCliente, direccionEnvio, lineas, total, reloj.instant()));
        carrito.vaciar();
        repositorioCarrito.save(carrito);
        log.info("Pedido realizado: cliente={} pedido={} lineas={} total={}", idCliente, pedido.getId(),
                lineas.size(), total);
        return aDTO(pedido, Map.of());
    }

    // HU-27 Historial de compras: los pedidos del cliente, los mas recientes primero, separados entre los que
    // siguen en curso y los que ya han terminado. Cada linea lleva la valoracion que le dio el cliente, si la hay.
    public HistorialPedidosDTO historial(String idCliente) {
        List<Pedido> pedidos = repositorioPedido.buscarPorCliente(new ObjectId(idCliente),
                Sort.by(Sort.Direction.DESC, "fecha"));
        Map<String, Integer> valoraciones = valoracionesPorLinea(idCliente);
        List<PedidoDTO> enProceso = new ArrayList<>();
        List<PedidoDTO> historicos = new ArrayList<>();
        for (Pedido pedido : pedidos) {
            (pedido.getEstado().enProceso() ? enProceso : historicos).add(aDTO(pedido, valoraciones));
        }
        return new HistorialPedidosDTO(enProceso, historicos);
    }

    // HU-28 Cancelar pedido: solo mientras esta pagado y sin enviar. Las unidades vuelven al stock de cada producto.
    public PedidoDTO cancelar(String idCliente, String idPedido) {
        Pedido pedido = pedidoDelCliente(idCliente, idPedido);
        if (!pedido.sePuedeCancelar()) {
            throw new EstadoPedidoNoPermitidoException(MENSAJE_NO_CANCELABLE);
        }
        for (LineaPedido linea : pedido.getLineas()) {
            devolverStock(linea.getIdProducto(), linea.getCantidad());
        }
        pedido.cancelar();
        log.info("Pedido cancelado: cliente={} pedido={}", idCliente, idPedido);
        return aDTO(repositorioPedido.save(pedido), valoracionesPorLinea(idCliente));
    }

    // HU-30 El cliente confirma que ha recibido el pedido y este pasa a FINALIZADO; desde entonces puede valorarlo.
    public PedidoDTO confirmarRecepcion(String idCliente, String idPedido) {
        Pedido pedido = pedidoDelCliente(idCliente, idPedido);
        if (!pedido.sePuedeConfirmarRecepcion()) {
            throw new EstadoPedidoNoPermitidoException(MENSAJE_NO_CONFIRMABLE);
        }
        pedido.finalizar();
        log.info("Pedido finalizado por el cliente: cliente={} pedido={}", idCliente, idPedido);
        return aDTO(repositorioPedido.save(pedido), valoracionesPorLinea(idCliente));
    }

    // Pedido del cliente. Uno de otro cliente se trata igual que uno inexistente (404), para no revelar que existe.
    Pedido pedidoDelCliente(String idCliente, String idPedido) {
        if (idPedido == null || !ObjectId.isValid(idPedido)) {
            throw new PedidoNoEncontradoException();
        }
        return repositorioPedido.findById(idPedido)
                .filter(encontrado -> idCliente.equals(encontrado.getIdCliente()))
                .orElseThrow(PedidoNoEncontradoException::new);
    }

    private static DireccionEnvio validarDireccion(DireccionEnvioDTO direccion) {
        if (direccion == null || vacio(direccion.calle()) || vacio(direccion.ciudad())
                || vacio(direccion.codigoPostal())) {
            throw new PedidoInvalidoException(MENSAJE_DIRECCION_INCOMPLETA);
        }
        String calle = direccion.calle().strip();
        String ciudad = direccion.ciudad().strip();
        String codigoPostal = direccion.codigoPostal().strip();
        if (calle.length() > LONGITUD_MAXIMA_CALLE || ciudad.length() > LONGITUD_MAXIMA_CIUDAD) {
            throw new PedidoInvalidoException(MENSAJE_DIRECCION_LARGA);
        }
        if (!CODIGO_POSTAL.matcher(codigoPostal).matches()) {
            throw new PedidoInvalidoException(MENSAJE_CODIGO_POSTAL_INVALIDO);
        }
        return new DireccionEnvio(calle, ciudad, codigoPostal);
    }

    private static boolean vacio(String texto) {
        return texto == null || texto.isBlank();
    }

    /** Devuelve unidades al stock. Si el producto ya no existe, no hay nada que devolver. */
    private void devolverStock(String idProducto, int cantidad) {
        Query consulta = new Query(Criteria.where("_id").is(new ObjectId(idProducto)));
        mongoTemplate.updateFirst(consulta, new Update().inc(CAMPO_STOCK, cantidad), Producto.class);
    }

    // Valoraciones del cliente indexadas por pedido y producto, para colocarlas en cada linea del historial
    private Map<String, Integer> valoracionesPorLinea(String idCliente) {
        Map<String, Integer> valoraciones = new HashMap<>();
        for (Valoracion valoracion : repositorioValoracion.buscarPorCliente(new ObjectId(idCliente))) {
            valoraciones.put(claveLinea(valoracion.getIdPedido(), valoracion.getIdProducto()), valoracion.getPuntos());
        }
        return valoraciones;
    }

    private static String claveLinea(String idPedido, String idProducto) {
        return idPedido + "/" + idProducto;
    }

    private static PedidoDTO aDTO(Pedido pedido, Map<String, Integer> valoraciones) {
        List<LineaPedidoDTO> lineas = pedido.getLineas().stream()
                .map(l -> new LineaPedidoDTO(l.getIdProducto(), l.getNombre(), l.getCantidad(), l.getPrecioUnitario(),
                        redondear(l.getPrecioUnitario() * l.getCantidad()),
                        valoraciones.get(claveLinea(pedido.getId(), l.getIdProducto()))))
                .toList();
        DireccionEnvio direccion = pedido.getDireccionEnvio();
        DireccionEnvioDTO direccionDTO = direccion == null ? null
                : new DireccionEnvioDTO(direccion.getCalle(), direccion.getCiudad(), direccion.getCodigoPostal());
        return new PedidoDTO(pedido.getId(), pedido.getEstado(), pedido.getFecha(), pedido.getPrecioTotal(),
                direccionDTO, lineas);
    }

    private static double redondear(double importe) {
        return Math.round(importe * 100) / 100.0;
    }
}
