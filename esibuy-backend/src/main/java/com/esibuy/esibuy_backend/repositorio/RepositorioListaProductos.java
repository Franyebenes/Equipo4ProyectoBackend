package com.esibuy.esibuy_backend.repositorio;

import java.util.List;
import java.util.Optional;

import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import com.esibuy.esibuy_backend.modelo.ListaProductos;

public interface RepositorioListaProductos extends MongoRepository<ListaProductos, String> {

    /** Misma collation que el indice unico (idUsuario, nombre) del script: "Moda" y "moda" son el mismo nombre. */
    String COLLATION_NOMBRE = "{ 'locale': 'es', 'strength': 2 }";

    // Favoritos primero y despues por nombre
    @Query(value = "{ 'idUsuario': ?0 }", sort = "{ 'porDefecto': -1, 'nombre': 1 }", collation = COLLATION_NOMBRE)
    List<ListaProductos> buscarPorUsuario(ObjectId idUsuario);

    // Solo si es del usuario: una lista ajena se trata igual que una inexistente
    @Query("{ '_id': ?0, 'idUsuario': ?1 }")
    Optional<ListaProductos> buscarPorIdYUsuario(ObjectId id, ObjectId idUsuario);

    @Query(value = "{ 'idUsuario': ?0, 'nombre': ?1 }", exists = true, collation = COLLATION_NOMBRE)
    boolean existePorUsuarioYNombre(ObjectId idUsuario, String nombre);

    @Query(value = "{ 'idUsuario': ?0, 'porDefecto': true }", exists = true)
    boolean existeFavoritos(ObjectId idUsuario);

    @Query("{ 'idUsuario': ?0, 'porDefecto': true }")
    Optional<ListaProductos> buscarFavoritos(ObjectId idUsuario);

    @Query(value = "{ 'idUsuario': ?0 }", count = true)
    long contarPorUsuario(ObjectId idUsuario);
}