package com.esibuy.esibuy_backend.servicio;

import java.util.Arrays;
import java.util.List;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import com.esibuy.esibuy_backend.excepcion.ConflictoNegocioException;
import com.esibuy.esibuy_backend.excepcion.OperacionNoPermitidaException;
import com.esibuy.esibuy_backend.excepcion.RecursoNoEncontradoException;
import com.esibuy.esibuy_backend.modelo.Direccion;
import com.esibuy.esibuy_backend.modelo.EstadoPedido;
import com.esibuy.esibuy_backend.modelo.Favorito;
import com.esibuy.esibuy_backend.modelo.ListaProductos;
import com.esibuy.esibuy_backend.modelo.MetodoPago;
import com.esibuy.esibuy_backend.modelo.Pago;
import com.esibuy.esibuy_backend.modelo.PagoSuscripcion;
import com.esibuy.esibuy_backend.modelo.Pedido;
import com.esibuy.esibuy_backend.modelo.Producto;
import com.esibuy.esibuy_backend.modelo.Rol;
import com.esibuy.esibuy_backend.modelo.TokenRecuperacion;
import com.esibuy.esibuy_backend.modelo.Usuario;
import com.esibuy.esibuy_backend.modelo.Valoracion;
import com.esibuy.esibuy_backend.repositorio.RepositorioUsuario;

/**
 * Baja voluntaria del usuario de la sesion.
 *
 * Cliente: no puede darse de baja con pedidos en curso (todos deben estar confirmados como recibidos o cancelados). Se
 * borran sus datos; los pedidos, valoraciones y justificantes de pago pasan al cliente ficticio.
 * Vendedor: se borran sus productos; los pedidos se quedan en el historial de los clientes. No puede darse de baja con
 * pedidos pendientes de entrega. Administrador: no puede darse de baja; solo lo elimina otro administrador.
 *
 * No hay transacciones: el usuario se borra lo ultimo para que, si algo falla a medias, se pueda repetir la baja.
 */
@Service
public class ServicioBajaCuenta {

    static final String CODIGO_PEDIDOS_EN_CURSO = "VENDEDOR_CON_PEDIDOS_EN_CURSO";
    static final String MENSAJE_PEDIDOS_EN_CURSO =
            "No puedes darte de baja mientras tengas pedidos en curso. Complétalos e inténtalo de nuevo";
    static final String CODIGO_CLIENTE_CON_PEDIDOS = "CLIENTE_CON_PEDIDOS_EN_CURSO";
    static final String MENSAJE_CLIENTE_CON_PEDIDOS =
            "No puedes darte de baja mientras tengas pedidos en curso. Cancela los que aún no se han enviado o, "
                    + "cuando te entreguen los demás, confirma su recepción e inténtalo de nuevo";
    static final String MENSAJE_USUARIO_NO_ENCONTRADO = "El usuario no existe";

    private static final String CAMPO_ID_CLIENTE = "idCliente";
    private static final String CAMPO_ID_USUARIO = "idUsuario";
    private static final String CAMPO_ESTADO = "estado";
    private static final String CAMPO_DIRECCION_ENVIO = "direccionEnvio";

    private static final List<String> ESTADOS_PENDIENTES_DE_ENTREGA = Arrays.stream(EstadoPedido.values())
            .filter(EstadoPedido::pendienteDeEntrega)
            .map(Enum::name)
            .toList();

    // Para el cliente, un pedido entregado sigue en curso hasta que confirma que lo ha recibido
    private static final List<String> ESTADOS_EN_CURSO = Arrays.stream(EstadoPedido.values())
            .filter(EstadoPedido::enProceso)
            .map(Enum::name)
            .toList();

    private static final Logger log = LoggerFactory.getLogger(ServicioBajaCuenta.class);

    private final RepositorioUsuario repositorioUsuario;
    private final ServicioCarrito servicioCarrito;
    private final MongoTemplate mongoTemplate;

    public ServicioBajaCuenta(RepositorioUsuario repositorioUsuario, ServicioCarrito servicioCarrito,
                              MongoTemplate mongoTemplate) {
        this.repositorioUsuario = repositorioUsuario;
        this.servicioCarrito = servicioCarrito;
        this.mongoTemplate = mongoTemplate;
    }

    public void darDeBaja(String idUsuario) {
        Usuario usuario = repositorioUsuario.findById(idUsuario)
                .orElseThrow(() -> new RecursoNoEncontradoException(MENSAJE_USUARIO_NO_ENCONTRADO));
        Rol rol = usuario.getRoles().get(0);
        ObjectId id = new ObjectId(idUsuario);
        switch (rol) {
            case ADMIN -> throw new OperacionNoPermitidaException();
            case VENDEDOR -> borrarDatosDeVendedor(id);
            case CLIENTE, PREMIUM -> borrarDatosDeCliente(idUsuario, id);
        }
        mongoTemplate.remove(porCampo(CAMPO_ID_USUARIO, id), TokenRecuperacion.class);
        repositorioUsuario.deleteById(idUsuario);
        log.info("Baja de cuenta: id={}, rol={}", idUsuario, rol);
    }

    private void borrarDatosDeVendedor(ObjectId id) {
        Query pendientes = Query.query(Criteria.where("idVendedor").is(id)
                .and(CAMPO_ESTADO).in(ESTADOS_PENDIENTES_DE_ENTREGA));
        if (mongoTemplate.exists(pendientes, Pedido.class)) {
            throw new ConflictoNegocioException(CODIGO_PEDIDOS_EN_CURSO, MENSAJE_PEDIDOS_EN_CURSO);
        }
        mongoTemplate.remove(porCampo("idVendedor", id), Producto.class);
    }

    private void borrarDatosDeCliente(String idUsuario, ObjectId id) {
        Query enCurso = Query.query(Criteria.where(CAMPO_ID_CLIENTE).is(id).and(CAMPO_ESTADO).in(ESTADOS_EN_CURSO));
        if (mongoTemplate.exists(enCurso, Pedido.class)) {
            throw new ConflictoNegocioException(CODIGO_CLIENTE_CON_PEDIDOS, MENSAJE_CLIENTE_CON_PEDIDOS);
        }
        // Primero el carrito: sus unidades reservadas vuelven al stock
        servicioCarrito.liberarYBorrarCarrito(idUsuario);
        mongoTemplate.remove(porCampo(CAMPO_ID_USUARIO, id), Direccion.class);
        mongoTemplate.remove(porCampo(CAMPO_ID_USUARIO, id), ListaProductos.class);
        mongoTemplate.remove(porCampo(CAMPO_ID_CLIENTE, id), Favorito.class);
        mongoTemplate.remove(porCampo(CAMPO_ID_CLIENTE, id), MetodoPago.class);
        pasarAlClienteFicticio(id);
    }

    // Lo que se conserva sin datos del cliente. Todos sus pedidos han terminado, asi que ya no hace falta la direccion;
    // el esquema de la BBDD exige el campo direccionEnvio, por eso se deja vacio
    private void pasarAlClienteFicticio(ObjectId id) {
        Update cambiarCliente = new Update().set(CAMPO_ID_CLIENTE, new ObjectId(Pedido.ID_CLIENTE_FICTICIO));
        Query delCliente = porCampo(CAMPO_ID_CLIENTE, id);
        mongoTemplate.updateMulti(delCliente, cambiarCliente, Valoracion.class);
        mongoTemplate.updateMulti(delCliente, cambiarCliente, Pago.class);
        mongoTemplate.updateMulti(delCliente, cambiarCliente, PagoSuscripcion.class);
        mongoTemplate.updateMulti(delCliente,
                new Update().set(CAMPO_ID_CLIENTE, new ObjectId(Pedido.ID_CLIENTE_FICTICIO))
                        .set(CAMPO_DIRECCION_ENVIO, new Document()),
                Pedido.class);
    }

    private static Query porCampo(String campo, ObjectId valor) {
        return Query.query(Criteria.where(campo).is(valor));
    }
}
