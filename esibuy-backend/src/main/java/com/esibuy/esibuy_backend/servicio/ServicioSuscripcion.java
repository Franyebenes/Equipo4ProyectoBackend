package com.esibuy.esibuy_backend.servicio;

import java.time.Clock;
import java.time.YearMonth;
import java.util.List;

import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.esibuy.esibuy_backend.excepcion.ConflictoNegocioException;
import com.esibuy.esibuy_backend.excepcion.MetodoPagoInvalidoException;
import com.esibuy.esibuy_backend.excepcion.MetodoPagoNoEncontradoException;
import com.esibuy.esibuy_backend.excepcion.RecursoNoEncontradoException;
import com.esibuy.esibuy_backend.modelo.LineaCarrito;
import com.esibuy.esibuy_backend.modelo.MetodoPago;
import com.esibuy.esibuy_backend.modelo.PagoSuscripcion;
import com.esibuy.esibuy_backend.modelo.Producto;
import com.esibuy.esibuy_backend.modelo.Rol;
import com.esibuy.esibuy_backend.modelo.Usuario;
import com.esibuy.esibuy_backend.repositorio.RepositorioCarrito;
import com.esibuy.esibuy_backend.repositorio.RepositorioMetodoPago;
import com.esibuy.esibuy_backend.repositorio.RepositorioPagoSuscripcion;
import com.esibuy.esibuy_backend.repositorio.RepositorioProducto;
import com.esibuy.esibuy_backend.repositorio.RepositorioUsuario;

/** Pasar a Premium pagando la cuota con una tarjeta guardada (pago simulado) y volver al plan estandar (HU-34). */
@Service
public class ServicioSuscripcion {

    private static final Logger log = LoggerFactory.getLogger(ServicioSuscripcion.class);

    static final String CODIGO_YA_ES_PREMIUM = "YA_ES_PREMIUM";
    static final String CODIGO_NO_ES_PREMIUM = "NO_ES_PREMIUM";
    static final String CODIGO_NO_ES_CLIENTE = "NO_ES_CLIENTE";
    static final String MENSAJE_CADUCADA = "La tarjeta esta caducada. Elige otra";

    private final RepositorioUsuario repositorioUsuario;
    private final RepositorioMetodoPago repositorioMetodoPago;
    private final RepositorioPagoSuscripcion repositorioPago;
    private final RepositorioCarrito repositorioCarrito;
    private final RepositorioProducto repositorioProducto;
    private final ServicioCarrito servicioCarrito;
    private final Clock reloj;
    private final double precio;

    public ServicioSuscripcion(RepositorioUsuario repositorioUsuario, RepositorioMetodoPago repositorioMetodoPago,
                               RepositorioPagoSuscripcion repositorioPago, RepositorioCarrito repositorioCarrito,
                               RepositorioProducto repositorioProducto, ServicioCarrito servicioCarrito, Clock reloj,
                               @Value("${esibuy.premium.precio}") double precio) {
        this.repositorioUsuario = repositorioUsuario;
        this.repositorioMetodoPago = repositorioMetodoPago;
        this.repositorioPago = repositorioPago;
        this.repositorioCarrito = repositorioCarrito;
        this.repositorioProducto = repositorioProducto;
        this.servicioCarrito = servicioCarrito;
        this.reloj = reloj;
        this.precio = precio;
    }

    public double precio() {
        return precio;
    }

    public ResultadoPremium pasarAPremium(String idCliente, String idMetodoPago) {
        Usuario usuario = buscarUsuario(idCliente);
        Rol actual = usuario.getRoles().get(0);
        if (actual == Rol.PREMIUM) {
            throw new ConflictoNegocioException(CODIGO_YA_ES_PREMIUM, "Ya eres Premium");
        }
        if (actual != Rol.CLIENTE) { // El @PreAuthorize ya lo impide; se repite por si se usa desde otro sitio
            throw new ConflictoNegocioException(CODIGO_NO_ES_CLIENTE, "Solo los clientes pueden pasar a Premium");
        }

        // 1. La tarjeta se comprueba antes de tocar nada: suya y no caducada 
        MetodoPago tarjeta = tarjetaDelCliente(idCliente, idMetodoPago);
        if (estaCaducada(tarjeta.getCaducidad())) {
            throw new MetodoPagoInvalidoException(MENSAJE_CADUCADA);
        }

        // 2. Pago simulado aprobado: el cliente pasa a Premium y se guarda el justificante
        usuario.cambiarTipoCliente(Rol.PREMIUM);
        Usuario guardado = repositorioUsuario.save(usuario);
        PagoSuscripcion pago = repositorioPago.save(
                new PagoSuscripcion(idCliente, tarjeta.getUltimosDigitos(), precio, reloj.instant()));
        log.info("Cliente pasa a Premium: cliente={} importe={} ultimosDigitos={}", idCliente, precio,
                tarjeta.getUltimosDigitos());
        return new ResultadoPremium(guardado, pago);
    }

    /**
     * Vuelve al plan estandar. Los productos solo Premium salen del carrito y
     * su stock vuelve al producto: ServicioPedido.realizar no comprueba soloPremium, asi que sin esto podria comprarlos.
     */
    public ResultadoBajaPremium volverAEstandar(String idCliente) {
        Usuario usuario = buscarUsuario(idCliente);
        if (usuario.getRoles().get(0) != Rol.PREMIUM) {
            throw new ConflictoNegocioException(CODIGO_NO_ES_PREMIUM, "Ya tienes el plan estandar");
        }
        usuario.cambiarTipoCliente(Rol.CLIENTE);
        Usuario guardado = repositorioUsuario.save(usuario);
        int quitados = quitarDelCarritoLosSoloPremium(idCliente);
        log.info("Cliente vuelve al plan estandar: cliente={} productosQuitados={}", idCliente, quitados);
        return new ResultadoBajaPremium(guardado, quitados);
    }

    private int quitarDelCarritoLosSoloPremium(String idCliente) {
        List<String> ids = repositorioCarrito.buscarPorCliente(new ObjectId(idCliente))
                .map(carrito -> carrito.getLineas().stream().map(LineaCarrito::getIdProducto).toList())
                .orElse(List.of());
        if (ids.isEmpty()) {
            return 0;
        }
        List<String> soloPremium = repositorioProducto.findAllById(ids).stream()
                .filter(producto -> Boolean.TRUE.equals(producto.getSoloPremium()))
                .map(Producto::getId)
                .toList();
        soloPremium.forEach(idProducto -> servicioCarrito.eliminar(idCliente, idProducto)); // devuelve el stock
        return soloPremium.size();
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

    private Usuario buscarUsuario(String idCliente) {
        return repositorioUsuario.findById(idCliente)
                .orElseThrow(() -> new RecursoNoEncontradoException("El usuario no existe"));
    }
}