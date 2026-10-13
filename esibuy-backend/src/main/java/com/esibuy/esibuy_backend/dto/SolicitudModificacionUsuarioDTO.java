package com.esibuy.esibuy_backend.dto;

import java.time.LocalDate;

//Request body for user modification requests.
public record SolicitudModificacionUsuarioDTO(String nombre, String apellidos, String dni, String telefono,
                                              String sede, LocalDate fechaNacimiento, String nombreComercial,
                                            String categoriaPrincipalId, String avatar) {
}