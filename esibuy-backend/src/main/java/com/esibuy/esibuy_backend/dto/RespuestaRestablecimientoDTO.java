package com.esibuy.esibuy_backend.dto;

// Respuesta al restablecimiento correcto de la contrasena. No inicia sesion: el usuario entra con su contrasena nueva.
public record RespuestaRestablecimientoDTO(String mensaje) {

    public static final String MENSAJE_CORRECTO = "Contraseña restablecida correctamente";

    public static RespuestaRestablecimientoDTO correcta() {
        return new RespuestaRestablecimientoDTO(MENSAJE_CORRECTO);
    }
}
