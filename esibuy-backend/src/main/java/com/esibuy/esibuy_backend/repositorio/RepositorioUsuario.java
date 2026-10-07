package com.esibuy.esibuy_backend.repositorio;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import com.esibuy.esibuy_backend.modelo.Usuario;
import com.esibuy.esibuy_backend.modelo.EstadoUsuario;
import com.esibuy.esibuy_backend.modelo.Rol;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.Optional;


public interface RepositorioUsuario extends MongoRepository<Usuario, String> {

    /**
     * Comparacion sin distinguir mayusculas (strength 2) pero si acentos, en espanol. Debe coincidir con la
     * collation del indice unico de perfil.nombreComercial del script de BBDD: asi la consulta usa el indice y
     * aplica el mismo criterio que la restriccion de unicidad.
     */
    String COLLATION_NOMBRE_COMERCIAL = "{ 'locale': 'es', 'strength': 2 }";

    // El servicio guarda y consulta el email ya normalizado (minusculas), asi que basta la igualdad exacta.
    @Query(value = "{ 'email': ?0 }", exists = true)
    boolean existePorEmail(String email);

    @Query(value = "{ 'perfil.nombreComercial': ?0 }", exists = true, collation = COLLATION_NOMBRE_COMERCIAL)
    boolean existePorNombreComercial(String nombreComercial);

    //listado por defecto de todos los usuarios, sin filtrar por estado
    Page<Usuario> findByEstadoNot(EstadoUsuario estado, Pageable paginacion);

    // lista de usuarios con un estado concreto (activo, bloqueado, desactivado, eliminado)
    Page<Usuario> findByEstado(EstadoUsuario estado, Pageable paginacion);

    @Query("{ 'rol': ?0, 'estado': { $ne: ?1 } }")
    Page<Usuario> findPorRolExcluyendoEstado(Rol rol, EstadoUsuario estadoExcluido, Pageable paginacion);

    /** NEW - Listing filtered by role AND an exact state. ?0 = role, ?1 = state. */
    @Query("{ 'rol': ?0, 'estado': ?1 }")
    Page<Usuario> findPorRolYEstado(Rol rol, EstadoUsuario estado, Pageable paginacion);

    
    Optional<Usuario> findByIdAndEstadoNot(String id, EstadoUsuario estado);

}
