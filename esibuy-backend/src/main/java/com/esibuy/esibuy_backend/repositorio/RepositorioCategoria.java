package com.esibuy.esibuy_backend.repositorio;

import com.esibuy.esibuy_backend.modelo.Categoria;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface RepositorioCategoria extends MongoRepository<Categoria, String> {
    boolean existsByNombreIgnoreCase(String nombre);
}
