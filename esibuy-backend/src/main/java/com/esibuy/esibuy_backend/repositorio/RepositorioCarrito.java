package com.esibuy.esibuy_backend.repositorio;

import java.util.Optional;

import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import com.esibuy.esibuy_backend.modelo.Carrito;

public interface RepositorioCarrito extends MongoRepository<Carrito, String> {

    // idCliente se guarda como ObjectId: se consulta con un ObjectId para que coincida el tipo (igual que en productos)
    @Query("{ 'idCliente': ?0 }")
    Optional<Carrito> buscarPorCliente(ObjectId idCliente);
}