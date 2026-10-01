package com.esibuy.esibuy_backend.repositorio;

import com.esibuy.esibuy_backend.modelo.Usuario;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

public interface RepositorioUsuario extends MongoRepository<Usuario, String> {

    @Query(value = "{ 'email': ?0 }", exists = true)
    boolean existePorEmail(String email);

    /**
     * TODO: el servicio pasa el nombre normalizado (minusculas y espacios colapsados). Para que coincida con
     * el guardado (con su capitalizacion original) hace falta una comparacion sin distinguir mayusculas
     * (p. ej. con collation de fuerza 2) o guardar tambien una version normalizada. Ver CP-REG-43.
     */
    @Query(value = "{ 'perfil.nombreComercial': ?0 }", exists = true)
    boolean existePorNombreComercial(String nombreComercial);
}
