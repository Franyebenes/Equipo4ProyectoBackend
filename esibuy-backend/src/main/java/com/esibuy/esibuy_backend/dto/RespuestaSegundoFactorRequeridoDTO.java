package com.esibuy.esibuy_backend.dto;

/**
 * Respuesta del primer paso del login cuando la cuenta tiene el 2FA activo: la contrasena era correcta pero falta el codigo.
 * No lleva ningun dato del usuario (ni id, ni correo, ni rol): todavia no ha entrado.
 */
public record RespuestaSegundoFactorRequeridoDTO(boolean requiereSegundoFactor, String mensaje) {
}
