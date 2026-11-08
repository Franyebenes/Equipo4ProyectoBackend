package com.esibuy.esibuy_backend.servicio;

import java.util.Arrays;
import java.util.List;

import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import com.esibuy.esibuy_backend.modelo.EstadoPedido;
import com.esibuy.esibuy_backend.modelo.Pedido;

/** Busca en "pedidos" un pedido en curso del cliente que se envie a esa direccion de la libreta. */
@Component
public class ComprobadorUsoDireccionMongo implements ComprobadorUsoDireccion {

    static final String CAMPO_CLIENTE = "idCliente";
    static final String CAMPO_DIRECCION = "direccionEnvio.idDireccion";
    static final String CAMPO_ESTADO = "estado";
    /** Todos los que aun no estan FINALIZADO ni CANCELADO (de PENDIENTE a ENTREGADO). */
    static final List<String> ESTADOS_EN_CURSO = Arrays.stream(EstadoPedido.values())
            .filter(EstadoPedido::enProceso)
            .map(Enum::name)
            .toList();

    private final MongoTemplate mongoTemplate;

    public ComprobadorUsoDireccionMongo(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public boolean estaEnPedidoEnCurso(String idUsuario, String idDireccion) {
        Query consulta = Query.query(Criteria.where(CAMPO_CLIENTE).is(new ObjectId(idUsuario))
                .and(CAMPO_DIRECCION).is(new ObjectId(idDireccion))
                .and(CAMPO_ESTADO).in(ESTADOS_EN_CURSO));
        return mongoTemplate.exists(consulta, Pedido.class);
    }
}