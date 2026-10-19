package com.esibuy.esibuy_backend.servicio;

import java.util.Optional;

/*
 * Limites de la recuperacion de contrasena, independientes del bloqueo del login. Es una interfaz para poder cambiar
 * el almacenamiento (hoy en memoria; mas adelante podria ser MongoDB) sin tocar la logica. Cada metodo comprueba y,
 * si permite, registra la solicitud en una sola operacion atomica; una solicitud rechazada no se registra.
 */
public interface LimitadorRecuperacion {

    // Registra una solicitud desde esa IP. @return vacio si se permite; IP_AGOTADA si ya agoto su cupo.
    Optional<MotivoLimiteRecuperacion> registrarSolicitudDeIp(String ip);

    // Registra una solicitud de esa cuenta (correo normalizado). @return vacio si se permite; si no, el motivo.
    Optional<MotivoLimiteRecuperacion> registrarSolicitudDeCuenta(String email);
}
