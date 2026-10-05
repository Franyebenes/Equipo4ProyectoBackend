package com.esibuy.esibuy_backend.repositorio;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.esibuy.esibuy_backend.modelo.Categoria;

public interface CategoriaDAO extends MongoRepository<Categoria, String> {

    boolean existsByNombreIgnoreCase(String nombre);
}