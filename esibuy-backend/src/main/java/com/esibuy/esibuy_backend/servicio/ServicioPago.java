package com.esibuy.esibuy_backend.servicio;

import java.time.Clock;
import java.time.YearMonth;

import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.esibuy.esibuy_backend.dto.PagarDTO;
import com.esibuy.esibuy_backend.dto.PedidoDTO;
import com.esibuy.esibuy_backend.dto.ResultadoPagoDTO;
import com.esibuy.esibuy_backend.excepcion.MetodoPagoInvalidoException;
import com.esibuy.esibuy_backend.excepcion.MetodoPagoNoEncontradoException;
import com.esibuy.esibuy_backend.modelo.MetodoPago;
import com.esibuy.esibuy_backend.modelo.Pago;
import com.esibuy.esibuy_backend.repositorio.RepositorioMetodoPago;
import com.esibuy.esibuy_backend.repositorio.RepositorioPago;

/**
 * Pago del carrito (HU-23). El pago es ficticio: se comprueba que la tarjeta elegida es del cliente y no esta
 * caducada, se crea el pedido PAGADO con ServicioPedido (HU-26, que calcula los precios con descuento y vacia el
 * carrito) y se guarda el justificante del pago.
 */
@Service
public class ServicioPago {

    private static final Logger log = LoggerFactory.getLogger(ServicioPago.class);

    private final ServicioPedido servicioPedido;
    private final RepositorioMetodoPago repositorioMetodoPago;
    private final RepositorioPago repositorioPago;
    private final Clock reloj;

    public ServicioPago(ServicioPedido servicioPedido, RepositorioMetodoPago repositorioMetodoPago,
                        RepositorioPago repositorioPago, Clock reloj) {
        this.servicioPedido = servicioPedido;
        this.repositorioMetodoPago = repositorioMetodoPago;
        this.repositorioPago = repositorioPago;
        this.reloj = reloj;
    }

    public ResultadoPagoDTO pagar(String idCliente, boolean premium, PagarDTO datos) {
        // 1. La tarjeta se comprueba ANTES de crear el pedido: si no vale, no se toca nada
        MetodoPago tarjeta = tarjetaDelCliente(idCliente, datos == null ? null : datos.idMetodoPago());
        if (estaCaducada(tarjeta.getCaducidad())) {
            throw new MetodoPagoInvalidoException("La tarjeta esta caducada. Elige otra");
        }
        // 2. El pedido lo crea ServicioPedido: valida la direccion, calcula el total y vacia el carrito
        PedidoDTO pedido = servicioPedido.realizar(idCliente, premium, datos.direccionEnvio());
        // 3. Justificante del pago
        repositorioPago.save(new Pago(pedido.id(), idCliente, tarjeta.getUltimosDigitos(), pedido.precioTotal(),
                reloj.instant()));
        log.info("Pago realizado: cliente={} pedido={} importe={} ultimosDigitos={}", idCliente, pedido.id(),
                pedido.precioTotal(), tarjeta.getUltimosDigitos());
        return new ResultadoPagoDTO(pedido, tarjeta.getUltimosDigitos());
    }

    // Solo una tarjeta del propio cliente: la de otro se trata igual que una que no existe (404)
    private MetodoPago tarjetaDelCliente(String idCliente, String idMetodoPago) {
        if (idMetodoPago == null || !ObjectId.isValid(idMetodoPago)) {
            throw new MetodoPagoNoEncontradoException();
        }
        return repositorioMetodoPago.buscarDelCliente(new ObjectId(idMetodoPago), new ObjectId(idCliente))
                .orElseThrow(MetodoPagoNoEncontradoException::new);
    }

    /** La tarjeta vale hasta el ultimo dia del mes indicado ("MM/AA"). */
    private boolean estaCaducada(String caducidad) {
        int mes = Integer.parseInt(caducidad.substring(0, 2));
        int anio = 2000 + Integer.parseInt(caducidad.substring(3));
        return YearMonth.of(anio, mes).isBefore(YearMonth.now(reloj));
    }
}