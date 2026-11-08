package com.esibuy.esibuy_backend.repositorio;

import java.util.List;
import java.util.Optional;

import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import com.esibuy.esibuy_backend.modelo.Direccion;

public interface RepositorioDireccion extends MongoRepository<Direccion, String> {

    // Primero la predeterminada y despues por orden de creacion
    @Query(value = "{ 'idUsuario': ?0 }", sort = "{ 'predeterminada': -1, '_id': 1 }")
    List<Direccion> buscarPorUsuario(ObjectId idUsuario);

    // Solo si es del usuario: una direccion ajena se trata igual que una inexistente
    @Query("{ '_id': ?0, 'idUsuario': ?1 }")
    Optional<Direccion> buscarPorIdYUsuario(ObjectId id, ObjectId idUsuario);

    @Query(value = "{ 'idUsuario': ?0 }", count = true)
    long contarPorUsuario(ObjectId idUsuario);
}