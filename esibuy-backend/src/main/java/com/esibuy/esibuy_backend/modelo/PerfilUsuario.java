package com.esibuy.esibuy_backend.modelo;

import java.time.LocalDate;

import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * Subdocumento "perfil" de usuarios. Se crea con {@link #builder()}.
 */
public class PerfilUsuario {

    @Field("nombre")
    private String nombre;
    @Field("apellidos")
    private String apellidos;
    @Field("dni")
    private String dni;
    @Field("fechaNacimiento")
    private LocalDate fechaNacimiento;
    @Field("telefono")
    private String telefono;
    @Field("imagen")
    private String avatarUrl;
    @Field("nombreComercial")
    private String nombreComercial;
    @Field(name = "idCategoriaPrincipal", targetType = FieldType.OBJECT_ID)
    private String categoriaPrincipalId;
    // datos del administrador
    @Field("sede")
    private String sede;
    @Field("fechaIncorporacion")
    private LocalDate fechaIncorporacion;

    /** Usado por Spring Data al leer de MongoDB. */
    protected PerfilUsuario() {
    }

    private PerfilUsuario(Builder builder) {
        this.nombre = builder.nombre;
        this.apellidos = builder.apellidos;
        this.dni = builder.dni;
        this.fechaNacimiento = builder.fechaNacimiento;
        this.telefono = builder.telefono;
        this.avatarUrl = builder.avatarUrl;
        this.nombreComercial = builder.nombreComercial;
        this.categoriaPrincipalId = builder.categoriaPrincipalId;
        this.sede = builder.sede;
        this.fechaIncorporacion = builder.fechaIncorporacion;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getNombre() {
        return nombre;
    }

    public String getApellidos() {
        return apellidos;
    }

    public String getDni() {
        return dni;
    }

    public LocalDate getFechaNacimiento() {
        return fechaNacimiento;
    }

    public String getTelefono() {
        return telefono;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public String getNombreComercial() {
        return nombreComercial;
    }

    public String getCategoriaPrincipalId() {
        return categoriaPrincipalId;
    }

    public String getSede() {
        return sede;
    }

    public LocalDate getFechaIncorporacion() {
        return fechaIncorporacion;
    }

    public void actualizarDatosPersonales(String nombre, String apellidos, String dni, String telefono,
                                          String sede, LocalDate fechaNacimiento, String nombreComercial, String categoriaPrincipalId, String avatarUrl) {
        this.nombre = nombre;
        this.apellidos = apellidos;
        // Un campo opcional que llega vacio (null) conserva su valor actual. No se usa Objects.requireNonNullElse:
        // lanza NullPointerException si el valor actual tambien es null (p. ej. la sede de un cliente).
        this.dni = conservarSiNulo(dni, this.dni);
        this.telefono = conservarSiNulo(telefono, this.telefono);
        this.sede = conservarSiNulo(sede, this.sede);
        this.fechaNacimiento = conservarSiNulo(fechaNacimiento, this.fechaNacimiento);
        this.nombreComercial = conservarSiNulo(nombreComercial, this.nombreComercial);
        this.categoriaPrincipalId = conservarSiNulo(categoriaPrincipalId, this.categoriaPrincipalId);
        this.avatarUrl = conservarSiNulo(avatarUrl, this.avatarUrl);
    }

 
    public void modificarDesdePerfil(String nombre, String apellidos, String telefono,
                                    LocalDate fechaNacimiento, String avatarUrl, String nombreComercial) {
        this.nombre = nombre;
        this.apellidos = apellidos;
        this.telefono = telefono;
        this.fechaNacimiento = fechaNacimiento;
        this.avatarUrl = avatarUrl;
        this.nombreComercial = nombreComercial;
    }

    // para aceptar localdate tambien
    private static <T> T conservarSiNulo(T nuevo, T actual) {
        return nuevo != null ? nuevo : actual;
    }

    /** Sin datos personales: puede acabar en un log. */
    @Override
    public String toString() {
        return "PerfilUsuario[nombreComercial=" + nombreComercial + ", categoriaPrincipalId=" + categoriaPrincipalId
                + "]";
    }

    public static final class Builder { 

        private String nombre;
        private String apellidos;
        private String dni;
        private LocalDate fechaNacimiento;
        private String telefono;
        private String avatarUrl;
        private String nombreComercial;
        private String categoriaPrincipalId;
        private String sede;
        private LocalDate fechaIncorporacion;

        private Builder() {
        }

        public Builder nombre(String nombre) {
            this.nombre = nombre;
            return this;
        }

        public Builder apellidos(String apellidos) {
            this.apellidos = apellidos;
            return this;
        }

        public Builder dni(String dni) {
            this.dni = dni;
            return this;
        }

        public Builder fechaNacimiento(LocalDate fechaNacimiento) {
            this.fechaNacimiento = fechaNacimiento;
            return this;
        }

        public Builder telefono(String telefono) {
            this.telefono = telefono;
            return this;
        }

        public Builder avatarUrl(String avatarUrl) {
            this.avatarUrl = avatarUrl;
            return this;
        }

        public Builder nombreComercial(String nombreComercial) {
            this.nombreComercial = nombreComercial;
            return this;
        }

        public Builder categoriaPrincipalId(String categoriaPrincipalId) {
            this.categoriaPrincipalId = categoriaPrincipalId;
            return this;
        }

        public Builder sede(String sede) {
            this.sede = sede;
            return this;
        }

        public Builder fechaIncorporacion(LocalDate fechaIncorporacion) {
            this.fechaIncorporacion = fechaIncorporacion;
            return this;
        }

        public PerfilUsuario build() {
            return new PerfilUsuario(this);
        }

        
    }
}
