package com.esibuy.esibuy_backend.servicio;

import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import com.esibuy.esibuy_backend.excepcion.CantidadNoValidaException;
import com.esibuy.esibuy_backend.excepcion.ProductoNoDisponibleException;
import com.esibuy.esibuy_backend.excepcion.StockInsuficienteException;
import com.esibuy.esibuy_backend.modelo.Carrito;
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

    private final RepositorioCarrito repositorioCarrito;
    private final RepositorioProducto repositorioProducto;
    private final MongoTemplate mongoTemplate;

    public ServicioCarrito(RepositorioCarrito repositorioCarrito, RepositorioProducto repositorioProducto,
                           MongoTemplate mongoTemplate) {
        this.repositorioCarrito = repositorioCarrito;
        this.repositorioProducto = repositorioProducto;
        this.mongoTemplate = mongoTemplate;
    }

    // HU-22.2 Anadir al carrito: si ya estaba, suma la cantidad
    public void anadir(String idCliente, String idProducto, int cantidad) {
        if (cantidad < 1) {
            throw new CantidadNoValidaException();
        }
        Producto producto = buscarDisponible(idProducto);
        reservarStock(producto.getId(), cantidad);
        Carrito carrito = repositorioCarrito.buscarPorCliente(new ObjectId(idCliente))
                .orElseGet(() -> new Carrito(idCliente));
        carrito.anadir(producto.getId(), cantidad);
        repositorioCarrito.save(carrito);
        log.info("Producto anadido al carrito: cliente={} producto={} cantidad={}", idCliente, idProducto, cantidad);
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
        Query consulta = new Query(Criteria.where("_id").is(new ObjectId(idProducto)).and("stock").gte(cantidad));
        UpdateResult resultado = mongoTemplate.updateFirst(consulta, new Update().inc("stock", -cantidad), Producto.class);
        if (resultado.getModifiedCount() == 0) {
            throw new StockInsuficienteException();
        }
    }
}