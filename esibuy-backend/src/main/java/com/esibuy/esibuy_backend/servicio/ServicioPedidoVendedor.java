package com.esibuy.esibuy_backend.servicio;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.esibuy.esibuy_backend.dto.DireccionEnvioDTO;
import com.esibuy.esibuy_backend.dto.LineaPedidoRecibidoDTO;
import com.esibuy.esibuy_backend.dto.PedidoRecibidoDTO;
import com.esibuy.esibuy_backend.dto.PedidoRecibidoDetalleDTO;
import com.esibuy.esibuy_backend.excepcion.PedidoNoEncontradoException;
import com.esibuy.esibuy_backend.modelo.DireccionEnvio;
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
 */
@Service
public class ServicioPedidoVendedor {

    /** Nombre que se muestra si el cliente que hizo el pedido ya no existe. */
    static final String CLIENTE_NO_DISPONIBLE = "Cliente eliminado";

    private static final Sort MAS_RECIENTES_PRIMERO = Sort.by(Sort.Direction.DESC, "fecha");

    private final RepositorioPedido repositorioPedido;
    private final RepositorioUsuario repositorioUsuario;

    public ServicioPedidoVendedor(RepositorioPedido repositorioPedido, RepositorioUsuario repositorioUsuario) {
        this.repositorioPedido = repositorioPedido;
        this.repositorioUsuario = repositorioUsuario;
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
        Pedido pedido = pedidoDelVendedor(idVendedor, idPedido);
        Map<String, String> clientes = nombresDeClientes(Collections.singleton(pedido.getIdCliente()));
        List<LineaPedidoRecibidoDTO> lineas = pedido.getLineas().stream()
                .map(ServicioPedidoVendedor::aLineaDTO)
                .toList();
        return new PedidoRecibidoDetalleDTO(pedido.getId(), pedido.getFecha(), nombreDelCliente(pedido, clientes),
                pedido.getPrecioTotal(), pedido.getEstado(), unidades(pedido),
                aDireccionDTO(pedido.getDireccionEnvio()), lineas);
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
