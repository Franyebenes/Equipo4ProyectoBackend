package com.esibuy.esibuy_backend.modelo;

/**
 * Estado de un token de recuperacion de contrasena. Se distingue USADO (el usuario ya cambio la contrasena con el
 * enlace) de INVALIDADO (una solicitud posterior lo sustituyo): reutilizar un USADO es una alerta de seguridad.
 */
public enum EstadoToken {
    VIGENTE, USADO, INVALIDADO
}
