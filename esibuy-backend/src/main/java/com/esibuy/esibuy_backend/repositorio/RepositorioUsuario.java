package com.esibuy.esibuy_backend.repositorio;

import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import com.esibuy.esibuy_backend.modelo.Usuario;

public interface RepositorioUsuario extends MongoRepository<Usuario, String> {

    /*
     * Comparacion sin distinguir mayusculas (strength 2) pero si acentos, en español. Debe coincidir con la
     * collation del indice unico de perfil.nombreComercial del script de BBDD: asi la consulta usa el indice y
     * aplica el mismo criterio que la restriccion de unicidad.
     */
    String COLLATION_NOMBRE_COMERCIAL = "{ 'locale': 'es', 'strength': 2 }";

    // El servicio guarda y consulta el email ya normalizado (minusculas), asi que basta la igualdad exacta.
    @Query(value = "{ 'email': ?0 }", exists = true)
    boolean existePorEmail(String email);

    @Query(value = "{ 'perfil.nombreComercial': ?0 }", exists = true, collation = COLLATION_NOMBRE_COMERCIAL)
    boolean existePorNombreComercial(String nombreComercial);

    // Usuario con ese email exacto, ya normalizado (el servicio de autenticacion lo recorta y lo pasa a minusculas antes de consultar). Se apoya en el indice unico de email.
    @Query("{ 'email': ?0 }")
    Optional<Usuario> buscarPorEmail(String email);
}
