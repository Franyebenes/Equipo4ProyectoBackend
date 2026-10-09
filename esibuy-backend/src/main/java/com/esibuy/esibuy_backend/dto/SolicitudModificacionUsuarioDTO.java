package com.esibuy.esibuy_backend.dto;

//Request body for user modification requests.
public record SolicitudModificacionUsuarioDTO(String nombre, String apellidos, String dni, String telefono,
                                              String sede) {
}