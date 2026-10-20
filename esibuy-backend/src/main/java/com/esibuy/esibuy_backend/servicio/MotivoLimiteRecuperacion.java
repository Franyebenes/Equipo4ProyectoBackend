package com.esibuy.esibuy_backend.servicio;

/*
 * Por que se ha limitado una solicitud de recuperacion de contrasena. Solo es para la auditoria: hacia el cliente la
 * respuesta es siempre la misma.
 */
public enum MotivoLimiteRecuperacion {
    // La IP ha agotado sus solicitudes en la ventana.
    IP_AGOTADA,
    // La cuenta ya recibio una solicitud hace menos del tiempo de espera.
    COOLDOWN,
    // La cuenta ya ha recibido el maximo de solicitudes en la ventana diaria.
    LIMITE_DIARIO
}
