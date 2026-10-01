package com.esibuy.esibuy_backend.modelo;

import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

import java.time.LocalDate;

/**
 * Subdocumento "profile" de users.
 *
 * TODO: constructor o builder. Los campos opcionales (telefono, nombre comercial...) NO deben
 * guardarse a null ni vacios (CP-INT-04): hay que omitirlos del documento.
 */
public class PerfilUsuario {

    @Field("firstName")
    private String nombre;
    @Field("lastName")
    private String apellidos;
    private String dni;
    @Field("birthDate")
    private LocalDate fechaNacimiento;
    @Field("phone")
    private String telefono;
    @Field("avatarUrl")
    private String avatarUrl;
    @Field("tradeName")
    private String nombreComercial;
    @Field(name = "mainCategoryId", targetType = FieldType.OBJECT_ID)
    private String categoriaPrincipalId;

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
}
