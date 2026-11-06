package com.esibuy.esibuy_backend.servicio;

import java.time.Clock;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import com.esibuy.esibuy_backend.dto.DireccionEnvioDTO;
import com.esibuy.esibuy_backend.dto.LineaPedidoRecibidoDTO;
import com.esibuy.esibuy_backend.dto.PedidoRecibidoDTO;
import com.esibuy.esibuy_backend.dto.PedidoRecibidoDetalleDTO;
import com.esibuy.esibuy_backend.excepcion.EstadoPedidoNoPermitidoException;
import com.esibuy.esibuy_backend.excepcion.PedidoInvalidoException;
import com.esibuy.esibuy_backend.excepcion.PedidoNoEncontradoException;
import com.esibuy.esibuy_backend.modelo.DireccionEnvio;
import com.esibuy.esibuy_backend.modelo.EstadoPedido;
import com.esibuy.esibuy_backend.modelo.LineaPedido;
import com.esibuy.esibuy_backend.modelo.Pedido;
import com.esibuy.esibuy_backend.modelo.PerfilUsuario;
import com.esibuy.esibuy_backend.modelo.Usuario;
import com.esibuy.esibuy_backend.repositorio.RepositorioPedido;
import com.esibuy.esibuy_backend.repositorio.RepositorioUsuario;

/**
 * Pedidos recibidos por el vendedor de la sesion. Cada pedido es de un solo vendedor (campo idVendedor), asi que los
 * pedidos que contienen productos de su catalogo son justo los que tienen su id. Los pedidos de otro vendedor se
 * tratan igual que los que no existen (404), para no revelar que existen.
 *
 * El vendedor tambien hace avanzar el envio de sus pedidos (Procesar estado del pedido): solo hacia delante y nunca
 * en un pedido entregado, finalizado o cancelado. Cada cambio guarda la fecha en que se hizo.
 */
@Service
public class ServicioPedidoVendedor {

    /** Nombre que se muestra si el cliente que hizo el pedido ya no existe. */
    static final String CLIENTE_NO_DISPONIBLE = "Cliente eliminado";
    static final String MENSAJE_ESTADO_OBLIGATORIO = "Indica el nuevo estado del pedido";
    static final String MENSAJE_SIN_MAS_CAMBIOS =
            "Este pedido ya está entregado, finalizado o cancelado: su estado no se puede cambiar";
    static final String MENSAJE_SOLO_AVANZA = "El envío solo puede avanzar: elige un estado posterior al actual";
    static final String MENSAJE_CAMBIADO_A_LA_VEZ =
            "El pedido ha cambiado mientras lo actualizabas. Recarga la página y vuelve a intentarlo";

    private static final Sort MAS_RECIENTES_PRIMERO = Sort.by(Sort.Direction.DESC, "fecha");
    private static final String CAMPO_ESTADO = "estado";

    private static final Logger log = LoggerFactory.getLogger(ServicioPedidoVendedor.class);

    private final RepositorioPedido repositorioPedido;
    private final RepositorioUsuario repositorioUsuario;
    private final MongoTemplate mongoTemplate;
    private final Clock reloj;

    public ServicioPedidoVendedor(RepositorioPedido repositorioPedido, RepositorioUsuario repositorioUsuario,
                                  MongoTemplate mongoTemplate, Clock reloj) {
        this.repositorioPedido = repositorioPedido;
        this.repositorioUsuario = repositorioUsuario;
        this.mongoTemplate = mongoTemplate;
        this.reloj = reloj;
    }

    // Ver pedidos recibidos: los pedidos de los productos del vendedor, los mas recientes primero
    public List<PedidoRecibidoDTO> listar(String idVendedor) {
        List<Pedido> pedidos = repositorioPedido.buscarPorVendedor(new ObjectId(idVendedor), MAS_RECIENTES_PRIMERO);
        Map<String, String> clientes = nombresDeClientes(pedidos.stream().map(Pedido::getIdCliente).toList());
        return pedidos.stream()
                .map(pedido -> new PedidoRecibidoDTO(pedido.getId(), pedido.getFecha(),
                        nombreDelCliente(pedido, clientes), pedido.getPrecioTotal(), pedido.getEstado(),
                        unidades(pedido)))
                .toList();
    }

    // Ver pedidos recibidos: detalle de un pedido del vendedor, con la direccion de envio y sus lineas
    public PedidoRecibidoDetalleDTO detalle(String idVendedor, String idPedido) {
        return detalleDe(pedidoDelVendedor(idVendedor, idPedido));
    }

    // Procesar estado del pedido: el vendedor hace avanzar el envio de uno de sus pedidos y se guarda cuando lo hizo
    public PedidoRecibidoDetalleDTO cambiarEstado(String idVendedor, String idPedido, EstadoPedido nuevo) {
        if (nuevo == null) {
            throw new PedidoInvalidoException(MENSAJE_ESTADO_OBLIGATORIO);
        }
        Pedido pedido = pedidoDelVendedor(idVendedor, idPedido);
        EstadoPedido actual = pedido.getEstado();
        if (actual.siguientesDelEnvio().isEmpty()) {
            throw new EstadoPedidoNoPermitidoException(MENSAJE_SIN_MAS_CAMBIOS);
        }
        if (!actual.puedeAvanzarA(nuevo)) {
            throw new EstadoPedidoNoPermitidoException(MENSAJE_SOLO_AVANZA);
        }
        // Solo se cambia si sigue en el estado comprobado: si el cliente lo cancela a la vez, no se pisa su cambio
        Query sinCambiosDesdeLaLectura = Query.query(Criteria.where("_id").is(new ObjectId(pedido.getId()))
                .and(CAMPO_ESTADO).is(actual.name()));
        Update avance = new Update().set(CAMPO_ESTADO, nuevo.name()).set("fechaActualizacion", reloj.instant());
        Pedido actualizado = mongoTemplate.findAndModify(sinCambiosDesdeLaLectura, avance,
                FindAndModifyOptions.options().returnNew(true), Pedido.class);
        if (actualizado == null) {
            throw new EstadoPedidoNoPermitidoException(MENSAJE_CAMBIADO_A_LA_VEZ);
        }
        log.info("Estado de pedido actualizado por el vendedor: vendedor={} pedido={} {} -> {}", idVendedor,
                idPedido, actual, nuevo);
        return detalleDe(actualizado);
    }

    private PedidoRecibidoDetalleDTO detalleDe(Pedido pedido) {
        Map<String, String> clientes = nombresDeClientes(Collections.singleton(pedido.getIdCliente()));
        List<LineaPedidoRecibidoDTO> lineas = pedido.getLineas().stream()
                .map(ServicioPedidoVendedor::aLineaDTO)
                .toList();
        return new PedidoRecibidoDetalleDTO(pedido.getId(), pedido.getFecha(), nombreDelCliente(pedido, clientes),
                pedido.getPrecioTotal(), pedido.getEstado(), unidades(pedido), pedido.getFechaActualizacion(),
                pedido.getEstado().siguientesDelEnvio(), aDireccionDTO(pedido.getDireccionEnvio()), lineas);
    }

    // Pedido del vendedor. Uno de otro vendedor se trata igual que uno inexistente (404)
    Pedido pedidoDelVendedor(String idVendedor, String idPedido) {
        if (idPedido == null || !ObjectId.isValid(idPedido)) {
            throw new PedidoNoEncontradoException();
        }
        return repositorioPedido.findById(idPedido)
                .filter(encontrado -> idVendedor.equals(encontrado.getIdVendedor()))
                .orElseThrow(PedidoNoEncontradoException::new);
    }

    // Nombre visible de cada cliente indexado por su id, con una sola consulta para todos los pedidos
    private Map<String, String> nombresDeClientes(Collection<String> idsClientes) {
        Map<String, String> nombres = new HashMap<>();
        Set<String> ids = idsClientes.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (!ids.isEmpty()) {
            repositorioUsuario.findAllById(ids)
                    .forEach(usuario -> nombres.put(usuario.getId(), nombreVisible(usuario)));
        }
        return nombres;
    }

    private static String nombreDelCliente(Pedido pedido, Map<String, String> clientes) {
        return clientes.getOrDefault(pedido.getIdCliente(), CLIENTE_NO_DISPONIBLE);
    }

    // Nombre y apellidos del cliente; si no tiene ninguno de los dos, su email
    static String nombreVisible(Usuario usuario) {
        PerfilUsuario perfil = usuario.getPerfil();
        if (perfil != null) {
            String completo = (Objects.toString(perfil.getNombre(), "") + " "
                    + Objects.toString(perfil.getApellidos(), "")).strip();
            if (!completo.isEmpty()) {
                return completo;
            }
        }
        return usuario.getEmail();
    }

    private static int unidades(Pedido pedido) {
        return pedido.getLineas().stream().mapToInt(LineaPedido::getCantidad).sum();
    }

    private static LineaPedidoRecibidoDTO aLineaDTO(LineaPedido linea) {
        return new LineaPedidoRecibidoDTO(linea.getIdProducto(), linea.getNombre(), linea.getCantidad(),
                linea.getPrecioUnitario(), redondear(linea.getPrecioUnitario() * linea.getCantidad()));
    }

    // El vendedor no necesita el id de la libreta de direcciones del cliente: solo a donde enviarlo
    private static DireccionEnvioDTO aDireccionDTO(DireccionEnvio direccion) {
        return direccion == null ? null
                : new DireccionEnvioDTO(direccion.getCalle(), direccion.getCiudad(), direccion.getCodigoPostal());
    }

    private static double redondear(double importe) {
        return Math.round(importe * 100) / 100.0;
    }
}
