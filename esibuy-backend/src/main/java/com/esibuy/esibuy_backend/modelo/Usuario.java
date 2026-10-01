package com.esibuy.esibuy_backend.modelo;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.util.List;

/**
 * Coleccion "users".
 *
 * TODO: anadir constructor o builder para que el servicio pueda crearlo; conversor Rol <-> String
 * (CUSTOMER, PREMIUM, SELLER, ADMIN) y EstadoUsuario <-> String (ACTIVE, BLOCKED);
 * toString() que NO incluya passwordHash (CP-REG-12).
 */
@Document(collection = "users")
public class Usuario {

    @Id
    private String id;
    private String email;
    @Field("passwordHash")
    private String passwordHash;
    private List<Rol> roles;
    @Field("status")
    private EstadoUsuario estado;
    @Field("profile")
    private PerfilUsuario perfil;

    public String getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public List<Rol> getRoles() {
        return roles;
    }

    public EstadoUsuario getEstado() {
        return estado;
    }

    public PerfilUsuario getPerfil() {
        return perfil;
    }
}
