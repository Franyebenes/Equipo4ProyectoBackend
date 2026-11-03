package com.esibuy.esibuy_backend.repositorio;

import java.util.List;

import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import com.esibuy.esibuy_backend.modelo.Pedido;

public interface RepositorioPedido extends MongoRepository<Pedido, String> {

    // idCliente se guarda como ObjectId: se consulta con un ObjectId para que coincida el tipo y se use el indice
    @Query("{ 'idCliente': ?0 }")
    List<Pedido> buscarPorCliente(ObjectId idCliente, Sort orden);
}
