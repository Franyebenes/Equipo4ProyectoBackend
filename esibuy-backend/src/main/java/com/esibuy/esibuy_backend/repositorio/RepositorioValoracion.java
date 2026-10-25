package com.esibuy.esibuy_backend.repositorio;

import java.util.List;

import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import com.esibuy.esibuy_backend.modelo.Valoracion;

public interface RepositorioValoracion extends MongoRepository<Valoracion, String> {

    @Query("{ 'idCliente': ?0 }")
    List<Valoracion> buscarPorCliente(ObjectId idCliente);

    @Query(value = "{ 'idCliente': ?0, 'idPedido': ?1, 'idProducto': ?2 }", exists = true)
    boolean existe(ObjectId idCliente, ObjectId idPedido, ObjectId idProducto);
}
