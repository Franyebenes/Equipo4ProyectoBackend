package com.esibuy.esibuy_backend.servicio;

/*
 * Resultado de validar o consumir un token. El motivo es solo para la auditoria: hacia el cliente todos los casos no
 * validos dan el mismo error. El id del usuario solo viene informado cuando el token es VALIDO.
 *
 * @param motivo    por que es valido o no
 * @param idUsuario usuario al que pertenece el token (solo si es VALIDO)
 */
public record ResultadoToken(Motivo motivo, String idUsuario) {

    public enum Motivo {
        // Existe, esta vigente y no ha caducado. 
        VALIDO,
        // No existe, o no tiene el formato de un token. 
        DESCONOCIDO,
        // Vigente pero ya paso su hora de caducidad. 
        CADUCADO,
        // Ya se uso para cambiar la contrasena (reutilizarlo es una alerta de seguridad). 
        YA_USADO,
        // Lo sustituyo una solicitud posterior. 
        INVALIDADO
    }

    public boolean esValido() {
        return motivo == Motivo.VALIDO;
    }
}
