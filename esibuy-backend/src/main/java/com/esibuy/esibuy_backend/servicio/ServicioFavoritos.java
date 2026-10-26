package com.esibuy.esibuy_backend.servicio;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.esibuy.esibuy_backend.dto.ProductoClienteDTO;
import com.esibuy.esibuy_backend.excepcion.FavoritoNoEncontradoException;
import com.esibuy.esibuy_backend.excepcion.FavoritoRepetidoException;
import com.esibuy.esibuy_backend.excepcion.ProductoNoDisponibleException;
import com.esibuy.esibuy_backend.modelo.Favorito;
import com.esibuy.esibuy_backend.repositorio.RepositorioFavorito;
import com.esibuy.esibuy_backend.repositorio.RepositorioProducto;

/**
 * HU-31.1 Ver, HU-31.2 Anadir y HU-31.3 Eliminar favoritos. Siempre los del cliente de la sesion: nadie puede ver ni
 * cambiar los de otro. Se guardan en la base de datos, asi que se conservan entre sesiones.
 */
@Service
public class ServicioFavoritos {

    private static final Logger log = LoggerFactory.getLogger(ServicioFavoritos.class);

    private final RepositorioFavorito repositorioFavorito;
    private final RepositorioProducto repositorioProducto;
    private final ServicioCatalogo servicioCatalogo;
    private final Clock reloj;

    public ServicioFavoritos(RepositorioFavorito repositorioFavorito, RepositorioProducto repositorioProducto,
                             ServicioCatalogo servicioCatalogo, Clock reloj) {
        this.repositorioFavorito = repositorioFavorito;
        this.repositorioProducto = repositorioProducto;
        this.servicioCatalogo = servicioCatalogo;
        this.reloj = reloj;
    }

    /**
     * HU-31.1 Lista de favoritos: los ultimos anadidos primero, con la misma informacion que el catalogo. Los
     * productos que el vendedor ha desactivado o borrado no salen (no los puede ver nadie).
     */
    public List<ProductoClienteDTO> listar(String idCliente, boolean clientePremium) {
        List<String> idsProductos = favoritosDe(idCliente).stream().map(Favorito::getIdProducto).toList();
        return servicioCatalogo.productosActivos(idsProductos, clientePremium);
    }

    /** Ids de los productos favoritos del cliente, para marcar el corazon en el catalogo y en la ficha. */
    public List<String> idsFavoritos(String idCliente) {
        return favoritosDe(idCliente).stream().map(Favorito::getIdProducto).toList();
    }

    /** HU-31.2 Anadir a favoritos un producto activo. Si ya estaba, 409 y no se repite. */
    public void anadir(String idCliente, String idProducto) {
        comprobarProductoActivo(idProducto);
        ObjectId cliente = new ObjectId(idCliente);
        ObjectId producto = new ObjectId(idProducto);
        if (repositorioFavorito.existe(cliente, producto)) {
            throw new FavoritoRepetidoException();
        }
        try {
            repositorioFavorito.save(new Favorito(idCliente, idProducto, Instant.now(reloj)));
        } catch (DuplicateKeyException e) {
            // Dos peticiones a la vez con un indice unico en la BBDD: se queda la primera
            throw new FavoritoRepetidoException();
        }
        log.info("Favorito anadido: cliente={} producto={}", idCliente, idProducto);
    }

    /** HU-31.3 Quitar de favoritos. Si el producto no estaba en sus favoritos, 404 y no se hace nada. */
    public void eliminar(String idCliente, String idProducto) {
        if (idProducto == null || !ObjectId.isValid(idProducto)
                || repositorioFavorito.borrar(new ObjectId(idCliente), new ObjectId(idProducto)) == 0) {
            throw new FavoritoNoEncontradoException();
        }
        log.info("Favorito eliminado: cliente={} producto={}", idCliente, idProducto);
    }

    // Los ultimos anadidos primero
    private List<Favorito> favoritosDe(String idCliente) {
        return repositorioFavorito.buscarPorCliente(new ObjectId(idCliente)).stream()
                .sorted(Comparator.comparing(Favorito::getAnadidoEn,
                        Comparator.nullsLast(Comparator.<Instant>naturalOrder())).reversed())
                .toList();
    }

    // Solo se pueden marcar productos que el cliente puede ver: existentes y activos
    private void comprobarProductoActivo(String idProducto) {
        boolean activo = idProducto != null && ObjectId.isValid(idProducto)
                && repositorioProducto.findById(idProducto).map(p -> Boolean.TRUE.equals(p.getVisible())).orElse(false);
        if (!activo) {
            throw new ProductoNoDisponibleException();
        }
    }
}