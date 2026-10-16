package com.esibuy.esibuy_backend.servicio;

/** Cifrado de la conexion SMTP. NINGUNO solo se admite hacia localhost (servidor de pruebas Mailpit). */
public enum ModoTls {
    /** Conexion en claro que se eleva a TLS con STARTTLS (normalmente puerto 587). Es obligatorio elevarla. */
    STARTTLS,
    /** TLS desde el primer byte (normalmente puerto 465). */
    TLS_IMPLICITO,
    /** Sin cifrado. */
    NINGUNO
}
