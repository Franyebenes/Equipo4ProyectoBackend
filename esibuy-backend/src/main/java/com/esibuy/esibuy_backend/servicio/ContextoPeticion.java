package com.esibuy.esibuy_backend.servicio;

/**
 * Datos de la peticion HTTP que el servicio de autenticacion necesita pero que no vienen en el cuerpo: la IP del
 * cliente (para el limitador de intentos) y su user agent (para la auditoria).
 *
 * <p>ESQUELETO (TDD, fase 1). No esta en el plan: lo piden el limitador (fase 2) y la auditoria (fase 4), y fijarlo
 * ya evita cambiar la firma de {@code autenticar} y los tests cerrados mas adelante.
 */
public record ContextoPeticion(String ip, String userAgent) {
}
