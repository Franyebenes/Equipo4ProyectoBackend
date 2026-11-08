package com.esibuy.esibuy_backend.servicio;

import java.util.List;

import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.esibuy.esibuy_backend.dto.ProductoClienteDTO;
import com.esibuy.esibuy_backend.excepcion.ConflictoNegocioException;
import com.esibuy.esibuy_backend.excepcion.FavoritoNoEncontradoException;
import com.esibuy.esibuy_backend.excepcion.FavoritoRepetidoException;
import com.esibuy.esibuy_backend.excepcion.ProductoNoDisponibleException;
import com.esibuy.esibuy_backend.modelo.ListaProductos;
import com.esibuy.esibuy_backend.repositorio.RepositorioListaProductos;
import com.esibuy.esibuy_backend.repositorio.RepositorioProducto;

/**
 * HU-31.1 Ver, HU-31.2 Anadir y HU-31.3 Eliminar favoritos. Siempre los del cliente de la sesion: nadie puede ver ni
 * cambiar los de otro. Los favoritos son la lista por defecto "Favoritos" de Mis listas, asi que el corazon, la
 * pestana Favoritos y Mis listas ensenan siempre lo mismo.
 */
@Service
public class ServicioFavoritos {

    private static final Logger log = LoggerFactory.getLogger(ServicioFavoritos.class);

    private final ServicioListas servicioListas;
    private final RepositorioListaProductos repositorioListas;
    private final RepositorioProducto repositorioProducto;
    private final ServicioCatalogo servicioCatalogo;

    public ServicioFavoritos(ServicioListas servicioListas, RepositorioListaProductos repositorioListas,
                             RepositorioProducto repositorioProducto, ServicioCatalogo servicioCatalogo) {
        this.servicioListas = servicioListas;
        this.repositorioListas = repositorioListas;
        this.repositorioProducto = repositorioProducto;
        this.servicioCatalogo = servicioCatalogo;
    }

    /**
     * HU-31.1 Lista de favoritos: los ultimos anadidos primero, con la misma informacion que el catalogo. Los
     * productos que el vendedor ha desactivado o borrado no salen (no los puede ver nadie).
     */
    public List<ProductoClienteDTO> listar(String idCliente, boolean clientePremium) {
        return servicioCatalogo.productosActivos(idsFavoritos(idCliente), clientePremium);
    }

    /** Ids de los productos favoritos del cliente (los ultimos primero), para marcar el corazon. */
    public List<String> idsFavoritos(String idCliente) {
        return servicioListas.favoritos(idCliente).getIdProductos().reversed();
    }

    /** HU-31.2 Anadir a favoritos un producto activo. Si ya estaba, 409 y no se repite. */
    public void anadir(String idCliente, String idProducto) {
        comprobarProductoActivo(idProducto);
        ListaProductos favoritos = servicioListas.favoritos(idCliente);
        if (favoritos.contiene(idProducto)) {
            throw new FavoritoRepetidoException();
        }
        if (favoritos.getIdProductos().size() >= ServicioListas.MAXIMO_PRODUCTOS) {
            throw new ConflictoNegocioException(ServicioListas.CODIGO_LIMITE_PRODUCTOS,
                    "La lista ya tiene el maximo de " + ServicioListas.MAXIMO_PRODUCTOS + " productos");
        }
        favoritos.anadirProducto(idProducto);
        repositorioListas.save(favoritos);
        log.info("Favorito anadido: cliente={} producto={}", idCliente, idProducto);
    }

    /** HU-31.3 Quitar de favoritos. Si el producto no estaba en sus favoritos, 404 y no se hace nada. */
    public void eliminar(String idCliente, String idProducto) {
        if (idProducto == null || !ObjectId.isValid(idProducto)) {
            throw new FavoritoNoEncontradoException();
        }
        ListaProductos favoritos = servicioListas.favoritos(idCliente);
        if (!favoritos.quitarProducto(idProducto)) {
            throw new FavoritoNoEncontradoException();
        }
        repositorioListas.save(favoritos);
        log.info("Favorito eliminado: cliente={} producto={}", idCliente, idProducto);
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
