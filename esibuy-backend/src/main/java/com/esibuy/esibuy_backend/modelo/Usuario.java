package com.esibuy.esibuy_backend.modelo;

import java.util.List;
import java.util.Objects;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;


@Document(collection = "usuarios")
public class Usuario {

    @Id
    private String id;
    private String email;
    @Field("password")
    private String passwordHash;
    @Field("rol")
    private List<Rol> roles;
    @Field("estado")
    private EstadoUsuario estado;
    @Field("perfil")
    private PerfilUsuario perfil;

    /** Usado por Spring Data al leer de MongoDB. */
    protected Usuario() {
    }

    /** Crea un usuario nuevo con un unico rol, desactivado hasta que un administrador lo active. */
    public Usuario(String email, String passwordHash, Rol rol, PerfilUsuario perfil) {
        this.email = Objects.requireNonNull(email, "email");
        this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash");
        this.roles = List.of(Objects.requireNonNull(rol, "rol"));
        this.perfil = Objects.requireNonNull(perfil, "perfil");
        this.estado = EstadoUsuario.DESACTIVADO;
    }

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

    public void cambiarEstado(EstadoUsuario nuevoEstado) {
        this.estado = Objects.requireNonNull(nuevoEstado, "estado");
    }

    public void actualizarDatosPersonales(String nombre, String apellidos, String dni, String telefono,
                                          String sede) {
        perfil.actualizarDatosPersonales(nombre, apellidos, dni, telefono, sede);
    }

    @Override
    public String toString() {
        return "Usuario[id=" + id + ", roles=" + roles + ", estado=" + estado + "]";
    }
}
