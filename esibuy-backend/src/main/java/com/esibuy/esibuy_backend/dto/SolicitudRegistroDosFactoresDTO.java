package com.esibuy.esibuy_backend.dto;

/** Primer paso del 2FA en el registro: el correo para el que se quiere el QR. */
public record SolicitudRegistroDosFactoresDTO(String email) {
}
