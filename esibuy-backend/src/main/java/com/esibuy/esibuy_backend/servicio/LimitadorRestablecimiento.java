package com.esibuy.esibuy_backend.servicio;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/*
 * Limite de intentos de restablecer la contrasena por IP, en memoria y con ventana deslizante (10 por hora por
 * defecto). Cada intento cuenta aunque el token sea basura; solo se registran los permitidos, asi que insistir con el
 * cupo agotado no alarga el castigo. Comprobar y registrar es una unica operacion atomica por IP, y como mucho una vez
 * por minuto se olvidan las IP cuyo ultimo intento ya salio de la ventana. Es independiente del bloqueo del login y del
 * limite de solicitudes de recuperacion. Los contadores viven en la memoria de esta instancia.
 * Tests: LimitadorRestablecimientoTest (CP-RST-20 a CP-RST-28).
 */
public class LimitadorRestablecimiento {

    private static final String IP_DESCONOCIDA = "(ip desconocida)";
    private static final Duration PERIODO_PURGA = Duration.ofMinutes(1);

    private final PropiedadesRestablecimiento propiedades;
    private final Clock reloj;
    private final Map<String, Deque<Instant>> intentosPorIp = new ConcurrentHashMap<>();
    private final AtomicReference<Instant> ultimaPurga;

    public LimitadorRestablecimiento(PropiedadesRestablecimiento propiedades, Clock reloj) {
        this.propiedades = propiedades;
        this.reloj = reloj;
        this.ultimaPurga = new AtomicReference<>(reloj.instant());
    }

    // Registra un intento desde esa IP. @return true si se permite; false si ya agoto su cupo.
    public boolean registrarIntento(String ip) {
        String clave = ip == null || ip.isBlank() ? IP_DESCONOCIDA : ip.strip();
        Instant ahora = reloj.instant();
        purgarSiToca(ahora);

        AtomicBoolean permitido = new AtomicBoolean(true);
        intentosPorIp.compute(clave, (k, intentos) -> {
            Deque<Instant> vigentes = VentanasDeslizantes.vigentes(intentos, ahora, propiedades.ventanaIp());
            if (vigentes.size() >= propiedades.maxPorIp()) {
                permitido.set(false);
            } else {
                vigentes.addLast(ahora);
            }
            return vigentes;
        });
        return permitido.get();
    }

    // Para los tests: cuantas IP sigue recordando.
    int clavesSeguidas() {
        return intentosPorIp.size();
    }

    private void purgarSiToca(Instant ahora) {
        Instant anterior = ultimaPurga.get();
        if (ahora.isBefore(anterior.plus(PERIODO_PURGA)) || !ultimaPurga.compareAndSet(anterior, ahora)) {
            return;
        }
        VentanasDeslizantes.purgar(intentosPorIp, ahora, propiedades.ventanaIp());
    }
}
