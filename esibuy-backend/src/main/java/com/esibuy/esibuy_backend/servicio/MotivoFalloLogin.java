package com.esibuy.esibuy_backend.servicio;

/*
 * Motivo interno por el que se rechaza un inicio de sesion. Solo se escribe en el evento de auditoria: la respuesta al
 * cliente es siempre la misma, para no permitir enumerar cuentas.
 */
public enum MotivoFalloLogin {
    CORREO_NO_REGISTRADO,
    CONTRASENA_INCORRECTA,
    CUENTA_NO_ACTIVA,
    ROLES_INVALIDOS,
    BLOQUEO_TEMPORAL
}
