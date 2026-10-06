package com.esibuy.esibuy_backend.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Solicitud de registro recibida en {@code POST /api/auth/registro}. El campo {@value #CAMPO_TIPO_CUENTA} del JSON
 * decide en que record se lee:
 * <ul>
 *   <li>{@code "CLIENTE"} o {@code "PREMIUM"} → {@link SolicitudRegistroClienteDTO}</li>
 *   <li>{@code "VENDEDOR"} → {@link SolicitudRegistroVendedorDTO}</li>
 * </ul>
 * Cada tipo conserva sus propios campos: un cliente que envie campos de vendedor (o al reves) se rechaza con 400 por
 * campo desconocido, igual que cualquier otro campo no permitido.
 *
 * <p>Es {@code sealed}: si se anade un tipo de cuenta, el compilador obliga a tratarlo en el controlador. Los metodos
 * comunes los cumplen los records sin codigo adicional y permiten al servicio tratar los campos comunes de una vez.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = SolicitudRegistro.CAMPO_TIPO_CUENTA, visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = SolicitudRegistroClienteDTO.class, names = {"CLIENTE", "PREMIUM"}),
        @JsonSubTypes.Type(value = SolicitudRegistroVendedorDTO.class, name = "VENDEDOR")})
public sealed interface SolicitudRegistro permits SolicitudRegistroClienteDTO, SolicitudRegistroVendedorDTO {

    /** Nombre del campo del JSON que indica el tipo de cuenta. */
    String CAMPO_TIPO_CUENTA = "tipoCuenta";

    TipoCuenta tipoCuenta();

    String nombre();

    String apellidos();

    String dni();

    String email();

    String telefono();

    String avatar();

    String contrasena();

    String repetirContrasena();
}
