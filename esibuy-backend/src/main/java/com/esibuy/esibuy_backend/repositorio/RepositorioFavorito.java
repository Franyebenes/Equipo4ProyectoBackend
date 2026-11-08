package com.esibuy.esibuy_backend.repositorio;

import java.util.List;

import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import com.esibuy.esibuy_backend.modelo.Favorito;

// idCliente e idProducto se guardan como ObjectId: se consultan con ObjectId para que coincida el tipo
public interface RepositorioFavorito extends MongoRepository<Favorito, String> {

    @Query("{ 'idCliente': ?0 }")
    List<Favorito> buscarPorCliente(ObjectId idCliente);

    @Query(value = "{ 'idCliente': ?0, 'idProducto': ?1 }", exists = true)
    boolean existe(ObjectId idCliente, ObjectId idProducto);

    // Devuelve cuantos se han borrado: 0 si el producto no estaba en sus favoritos
    @Query(value = "{ 'idCliente': ?0, 'idProducto': ?1 }", delete = true)
    long borrar(ObjectId idCliente, ObjectId idProducto);
}
