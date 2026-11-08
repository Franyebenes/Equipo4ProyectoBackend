package com.esibuy.esibuy_backend.servicio;

import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.esibuy.esibuy_backend.excepcion.EstadoPedidoNoPermitidoException;
import com.esibuy.esibuy_backend.excepcion.ProductoNoEnPedidoException;
import com.esibuy.esibuy_backend.excepcion.ProductoYaValoradoException;
import com.esibuy.esibuy_backend.excepcion.ValoracionInvalidaException;
import com.esibuy.esibuy_backend.modelo.EstadoPedido;
import com.esibuy.esibuy_backend.modelo.Pedido;
import com.esibuy.esibuy_backend.modelo.Valoracion;
import com.esibuy.esibuy_backend.repositorio.RepositorioValoracion;

/**
 * HU-30 Valorar producto: el cliente puntua de 1 a 5 cada producto de un pedido que ya ha confirmado como recibido
 * (FINALIZADO). Cada producto se valora una sola vez por pedido y la valoracion no se puede cambiar.
 */
@Service
public class ServicioValoracion {

    static final String MENSAJE_PEDIDO_NO_FINALIZADO =
            "Solo puedes valorar los productos de un pedido que hayas confirmado como recibido";
    static final int PUNTOS_MINIMOS = 1;
    static final int PUNTOS_MAXIMOS = 5;

    private static final Logger log = LoggerFactory.getLogger(ServicioValoracion.class);

    private final RepositorioValoracion repositorioValoracion;
    private final ServicioPedido servicioPedido;

    public ServicioValoracion(RepositorioValoracion repositorioValoracion, ServicioPedido servicioPedido) {
        this.repositorioValoracion = repositorioValoracion;
        this.servicioPedido = servicioPedido;
    }

    public void valorar(String idCliente, String idPedido, String idProducto, Integer puntos) {
        if (puntos == null || puntos < PUNTOS_MINIMOS || puntos > PUNTOS_MAXIMOS) {
            throw new ValoracionInvalidaException();
        }
        Pedido pedido = servicioPedido.pedidoDelCliente(idCliente, idPedido);
        if (pedido.getEstado() != EstadoPedido.FINALIZADO) {
            throw new EstadoPedidoNoPermitidoException(MENSAJE_PEDIDO_NO_FINALIZADO);
        }
        if (pedido.buscarLinea(idProducto).isEmpty()) {
            throw new ProductoNoEnPedidoException();
        }
        if (repositorioValoracion.existe(new ObjectId(idCliente), new ObjectId(idPedido), new ObjectId(idProducto))) {
            throw new ProductoYaValoradoException();
        }
        try {
            repositorioValoracion.save(new Valoracion(idProducto, idCliente, idPedido, puntos));
        } catch (DuplicateKeyException e) {
            // Dos peticiones a la vez: el indice unico de la BBDD se queda con la primera
            throw new ProductoYaValoradoException();
        }
        log.info("Producto valorado: cliente={} pedido={} producto={} puntos={}", idCliente, idPedido, idProducto,
                puntos);
    }
}
