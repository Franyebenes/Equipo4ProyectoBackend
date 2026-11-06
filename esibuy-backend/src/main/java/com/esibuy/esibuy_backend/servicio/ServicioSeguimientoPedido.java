package com.esibuy.esibuy_backend.servicio;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

import com.esibuy.esibuy_backend.dto.PasoSeguimientoDTO;
import com.esibuy.esibuy_backend.dto.SeguimientoPedidoDTO;
import com.esibuy.esibuy_backend.excepcion.PedidoNoEncontradoException;
import com.esibuy.esibuy_backend.modelo.EstadoPedido;
import com.esibuy.esibuy_backend.modelo.EventoSeguimiento;
import com.esibuy.esibuy_backend.modelo.Pedido;
import com.esibuy.esibuy_backend.repositorio.RepositorioPedido;

/**
 * Estado del envio: el seguimiento de un pedido que ve el cliente. Solo el cliente que hizo el pedido puede
 * consultarlo; un pedido de otro se trata igual que uno que no existe (404).
 *
 * Se lee siempre de la base de datos, asi que muestra el estado mas reciente que haya registrado el vendedor. Los
 * pedidos anteriores a guardar el seguimiento no tienen la fecha de cada paso: de ellos se sabe cuando se hizo el
 * pedido y cuando cambio por ultima vez, y el resto de pasos alcanzados salen sin fecha.
 */
@Service
public class ServicioSeguimientoPedido {

    private final RepositorioPedido repositorioPedido;
    private final CalculadoraEntrega calculadoraEntrega;

    public ServicioSeguimientoPedido(RepositorioPedido repositorioPedido, CalculadoraEntrega calculadoraEntrega) {
        this.repositorioPedido = repositorioPedido;
        this.calculadoraEntrega = calculadoraEntrega;
    }

    // Estado del envio: estado actual, fechas y pasos del envio de un pedido del cliente de la sesion
    public SeguimientoPedidoDTO consultar(String idCliente, String idPedido) {
        Pedido pedido = pedidoDelCliente(idCliente, idPedido);
        EstadoPedido estado = pedido.getEstado();
        // Un pedido cancelado (o en un estado que no es del envio) no tiene seguimiento que mostrar
        boolean disponible = estado.esPasoDelEnvio() || estado == EstadoPedido.FINALIZADO;
        return new SeguimientoPedidoDTO(pedido.getId(), estado, pedido.getFecha(), pedido.getFechaActualizacion(),
                calculadoraEntrega.estimada(pedido), fechaEntrega(pedido), disponible,
                disponible ? pasos(pedido) : List.of());
    }

    // Pedido del cliente. Uno de otro cliente se trata igual que uno inexistente (404)
    private Pedido pedidoDelCliente(String idCliente, String idPedido) {
        if (idPedido == null || !ObjectId.isValid(idPedido)) {
            throw new PedidoNoEncontradoException();
        }
        return repositorioPedido.findById(idPedido)
                .filter(encontrado -> idCliente.equals(encontrado.getIdCliente()))
                .orElseThrow(PedidoNoEncontradoException::new);
    }

    // Los pasos del envio en orden. Si el cliente ya confirmo que lo recibio (FINALIZADO), todos estan completados
    private static List<PasoSeguimientoDTO> pasos(Pedido pedido) {
        List<EstadoPedido> recorrido = EstadoPedido.pasosDelEnvio();
        boolean recibido = pedido.getEstado() == EstadoPedido.FINALIZADO;
        int actual = recibido ? recorrido.size() - 1 : recorrido.indexOf(pedido.getEstado());
        List<PasoSeguimientoDTO> pasos = new ArrayList<>();
        for (int i = 0; i < recorrido.size(); i++) {
            EstadoPedido paso = recorrido.get(i);
            boolean completado = i <= actual;
            pasos.add(new PasoSeguimientoDTO(paso, completado, i == actual && !recibido,
                    completado ? fechaDelPaso(pedido, paso, i == actual) : null));
        }
        return pasos;
    }

    // Cuando llego a ese paso: su registro en el seguimiento. Sin el (pedidos antiguos), el pago es la fecha del
    // pedido y el ultimo paso alcanzado, la de su ultima actualizacion; de los intermedios no se sabe
    private static Instant fechaDelPaso(Pedido pedido, EstadoPedido paso, boolean esElUltimo) {
        Instant registrada = ultimaVezEn(pedido, paso);
        if (registrada != null) {
            return registrada;
        }
        if (paso == EstadoPedido.PAGADO) {
            return pedido.getFecha();
        }
        return esElUltimo ? pedido.getFechaActualizacion() : null;
    }

    // Cuando se entrego: al marcarlo como entregado o, si no se marco, cuando el cliente confirmo que lo recibio
    private static Instant fechaEntrega(Pedido pedido) {
        EstadoPedido estado = pedido.getEstado();
        if (estado != EstadoPedido.ENTREGADO && estado != EstadoPedido.FINALIZADO) {
            return null;
        }
        Instant entregado = ultimaVezEn(pedido, EstadoPedido.ENTREGADO);
        if (entregado != null) {
            return entregado;
        }
        Instant recibido = ultimaVezEn(pedido, EstadoPedido.FINALIZADO);
        return recibido != null ? recibido : pedido.getFechaActualizacion();
    }

    private static Instant ultimaVezEn(Pedido pedido, EstadoPedido estado) {
        List<EventoSeguimiento> seguimiento = pedido.getSeguimiento();
        for (int i = seguimiento.size() - 1; i >= 0; i--) {
            if (seguimiento.get(i).getEstado() == estado) {
                return seguimiento.get(i).getFecha();
            }
        }
        return null;
    }
}
