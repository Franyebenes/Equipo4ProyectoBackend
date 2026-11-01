package com.esibuy.esibuy_backend.repositorio;

import java.util.List;
import java.util.Optional;

import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import com.esibuy.esibuy_backend.modelo.MetodoPago;

public interface RepositorioMetodoPago extends MongoRepository<MetodoPago, String> {

    @Query("{ 'idCliente': ?0 }")
    List<MetodoPago> buscarPorCliente(ObjectId idCliente);

    // Solo si es de ese cliente: asi nadie puede borrar el metodo de pago de otro
    @Query("{ '_id': ?0, 'idCliente': ?1 }")
    Optional<MetodoPago> buscarDelCliente(ObjectId id, ObjectId idCliente);

    @Query(value = "{ 'idCliente': ?0 }", count = true)
    long contarPorCliente(ObjectId idCliente);
}