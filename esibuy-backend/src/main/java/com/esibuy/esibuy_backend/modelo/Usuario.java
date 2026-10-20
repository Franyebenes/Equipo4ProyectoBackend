package com.esibuy.esibuy_backend.modelo;

import java.time.Instant;
import java.time.LocalDate;
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
    @Field("contrasenaCambiadaEn")
    private Instant contrasenaCambiadaEn;
    // Segundo factor (TOTP); null en las cuentas que no lo han activado, que son todas las anteriores a la HdU del 2FA
    @Field("dosFactores")
    private DosFactores dosFactores;

    // Usado por Spring Data al leer de MongoDB. 
    protected Usuario() {
    }

    //Crea un usuario nuevo con un unico rol, desactivado hasta que un administrador lo active.
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

    // Instante del ultimo cambio de contrasena por recuperacion; null si nunca ha cambiado. Las sesiones iniciadas antes quedan invalidas.
    public Instant getContrasenaCambiadaEn() {
        return contrasenaCambiadaEn;
    }

    // Estado del segundo factor; null si nunca lo ha empezado a activar. Los cambios los hace RepositorioDosFactores.
    public DosFactores getDosFactores() {
        return dosFactores;
    }

    public boolean tieneDosFactoresActivo() {
        return dosFactores != null && dosFactores.isActivo();
    }

    // Sustituye el hash de la contrasena y anota cuando, para invalidar las sesiones iniciadas antes.
    public void cambiarContrasena(String nuevoHash, Instant cuando) {
        this.passwordHash = Objects.requireNonNull(nuevoHash, "nuevoHash");
        this.contrasenaCambiadaEn = Objects.requireNonNull(cuando, "cuando");
    }

    public void cambiarEstado(EstadoUsuario nuevoEstado) {
        this.estado = Objects.requireNonNull(nuevoEstado, "estado");
    }

    public void actualizarDatosPersonales(String nombre, String apellidos, String dni, String telefono,
                                          String sede, LocalDate fechaNacimiento, String nombreComercial,
                                          String categoriaPrincipalId, String avatarUrl) {
        perfil.actualizarDatosPersonales(nombre, apellidos, dni, telefono, sede,
                fechaNacimiento, nombreComercial, categoriaPrincipalId, avatarUrl);
    }

    @Override
    public String toString() {
        return "Usuario[id=" + id + ", roles=" + roles + ", estado=" + estado + "]";
    }
}
