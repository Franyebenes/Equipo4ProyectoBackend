package com.esibuy.esibuy_backend.repositorio;

import java.util.List;

import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import com.esibuy.esibuy_backend.modelo.Producto;

public interface RepositorioProducto extends MongoRepository<Producto, String> {

    // idVendedor se guarda como ObjectId: se consulta con un ObjectId para que coincida el tipo y se use el indice
    // idVendedor_1 del script de BBDD.
    @Query("{ 'idVendedor': ?0 }")
    List<Producto> buscarPorVendedor(ObjectId idVendedor);
}
