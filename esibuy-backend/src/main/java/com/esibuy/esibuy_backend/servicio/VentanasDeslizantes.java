package com.esibuy.esibuy_backend.servicio;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

/*
 * Operaciones comunes de los limitadores en memoria con ventana deslizante (recuperacion y restablecimiento): de cada
 * clave se guardan los instantes de sus solicitudes permitidas, en orden, y un instante sale de la ventana justo cuando
 * se cumple su duracion.
 */
final class VentanasDeslizantes {

    private VentanasDeslizantes() {
    }

    // Las solicitudes de la clave que siguen dentro de la ventana (una cola nueva si no habia ninguna).
    static Deque<Instant> vigentes(Deque<Instant> solicitudes, Instant ahora, Duration ventana) {
        Deque<Instant> vigentes = solicitudes == null ? new ArrayDeque<>() : solicitudes;
        while (!vigentes.isEmpty() && haSalido(vigentes.peekFirst(), ahora, ventana)) {
            vigentes.removeFirst();
        }
        return vigentes;
    }

    static boolean haSalido(Instant solicitud, Instant ahora, Duration ventana) {
        return !ahora.isBefore(solicitud.plus(ventana));
    }

    // Elimina las claves cuya ultima solicitud ya salio de la ventana. Cada clave se revisa con una operacion atomica.
    static void purgar(Map<String, Deque<Instant>> solicitudes, Instant ahora, Duration ventana) {
        for (String clave : solicitudes.keySet()) {
            solicitudes.computeIfPresent(clave, (k, cola) ->
                    cola.isEmpty() || haSalido(cola.peekLast(), ahora, ventana) ? null : cola);
        }
    }
}
