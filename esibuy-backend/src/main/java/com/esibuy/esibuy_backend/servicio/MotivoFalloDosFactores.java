package com.esibuy.esibuy_backend.servicio;

/*
 * Motivo por el que falla una operacion con el segundo factor. Solo se escribe en el evento de auditoria; al cliente le
 * llega el codigo de error del campo (CONTRASENA_INCORRECTA, CODIGO_INCORRECTO o ACTIVACION_NO_VIGENTE). CODIGO_REUTILIZADO
 * es un codigo TOTP correcto que ya se habia aceptado: al cliente le llega como CODIGO_INCORRECTO.
 */
public enum MotivoFalloDosFactores {
    CONTRASENA_INCORRECTA,
    CODIGO_INCORRECTO,
    CODIGO_REUTILIZADO,
    ACTIVACION_NO_VIGENTE
}
