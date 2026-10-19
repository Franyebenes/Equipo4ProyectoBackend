package com.esibuy.esibuy_backend.servicio;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/*
 * Implementacion en memoria de {@link LimitadorRecuperacion}, con ventanas deslizantes: de cada clave (cuenta o IP) se
 * guardan los instantes de sus solicitudes permitidas y se olvidan los que ya salieron de la ventana. Un instante sale
 * de la ventana justo cuando se cumple su duracion. Solo se registran las solicitudes permitidas, de modo que insistir
 * mientras se esta limitado no alarga el castigo.
 *
 * Es seguro entre hilos: cada clave se modifica en una unica operacion atomica del mapa (comprobar y registrar a la
 * vez). Para no acumular memoria, como mucho una vez por minuto se eliminan las claves cuya ultima solicitud ya salio
 * de su ventana, aprovechando las propias peticiones.
 *
 * Los contadores viven en la memoria de esta instancia: se pierden al reiniciar y no se comparten entre instancias.
 * Es independiente del bloqueo del login (no usa nada de LimitadorIntentosLogin).
 * Tests: LimitadorRecuperacionEnMemoriaTest (CP-LIM-10 a CP-LIM-29).
 */
public class LimitadorRecuperacionEnMemoria implements LimitadorRecuperacion {

    private static final String IP_DESCONOCIDA = "(ip desconocida)";
    private static final Duration PERIODO_PURGA = Duration.ofMinutes(1);

    private final PropiedadesRecuperacion propiedades;
    private final Clock reloj;
    private final Map<String, Deque<Instant>> solicitudesPorCuenta = new ConcurrentHashMap<>();
    private final Map<String, Deque<Instant>> solicitudesPorIp = new ConcurrentHashMap<>();
    private final AtomicReference<Instant> ultimaPurga;

    public LimitadorRecuperacionEnMemoria(PropiedadesRecuperacion propiedades, Clock reloj) {
        this.propiedades = propiedades;
        this.reloj = reloj;
        this.ultimaPurga = new AtomicReference<>(reloj.instant());
    }

    @Override
    public Optional<MotivoLimiteRecuperacion> registrarSolicitudDeIp(String ip) {
        String clave = ip == null || ip.isBlank() ? IP_DESCONOCIDA : ip.strip();
        Instant ahora = reloj.instant();
        purgarSiToca(ahora);

        AtomicReference<Optional<MotivoLimiteRecuperacion>> resultado = new AtomicReference<>(Optional.empty());
        solicitudesPorIp.compute(clave, (k, solicitudes) -> {
            Deque<Instant> vigentes = solicitudesVigentes(solicitudes, ahora, propiedades.ventanaIp());
            if (vigentes.size() >= propiedades.maxPorIp()) {
                resultado.set(Optional.of(MotivoLimiteRecuperacion.IP_AGOTADA));
            } else {
                vigentes.addLast(ahora);
            }
            return vigentes;
        });
        return resultado.get();
    }

    @Override
    public Optional<MotivoLimiteRecuperacion> registrarSolicitudDeCuenta(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("El correo de la cuenta es obligatorio");
        }
        Instant ahora = reloj.instant();
        purgarSiToca(ahora);

        AtomicReference<Optional<MotivoLimiteRecuperacion>> resultado = new AtomicReference<>(Optional.empty());
        solicitudesPorCuenta.compute(email, (k, solicitudes) -> {
            Deque<Instant> vigentes = solicitudesVigentes(solicitudes, ahora, propiedades.ventanaCuenta());
            if (vigentes.size() >= propiedades.maxPorCuenta()) {
                resultado.set(Optional.of(MotivoLimiteRecuperacion.LIMITE_DIARIO));
            } else if (!vigentes.isEmpty() && ahora.isBefore(vigentes.peekLast().plus(propiedades.cooldown()))) {
                resultado.set(Optional.of(MotivoLimiteRecuperacion.COOLDOWN));
            } else {
                vigentes.addLast(ahora);
            }
            return vigentes;
        });
        return resultado.get();
    }

    // Para los tests: cuantas claves (cuentas e IP) sigue recordando.
    int clavesSeguidas() {
        return solicitudesPorCuenta.size() + solicitudesPorIp.size();
    }

    // Las solicitudes de la clave que siguen dentro de la ventana (una cola nueva si no habia ninguna).
    private static Deque<Instant> solicitudesVigentes(Deque<Instant> solicitudes, Instant ahora, Duration ventana) {
        Deque<Instant> vigentes = solicitudes == null ? new ArrayDeque<>() : solicitudes;
        while (!vigentes.isEmpty() && haSalidoDeLaVentana(vigentes.peekFirst(), ahora, ventana)) {
            vigentes.removeFirst();
        }
        return vigentes;
    }

    private static boolean haSalidoDeLaVentana(Instant solicitud, Instant ahora, Duration ventana) {
        return !ahora.isBefore(solicitud.plus(ventana));
    }

    private void purgarSiToca(Instant ahora) {
        Instant anterior = ultimaPurga.get();
        if (ahora.isBefore(anterior.plus(PERIODO_PURGA)) || !ultimaPurga.compareAndSet(anterior, ahora)) {
            return;
        }
        purgar(solicitudesPorCuenta, ahora, propiedades.ventanaCuenta());
        purgar(solicitudesPorIp, ahora, propiedades.ventanaIp());
    }

    // Cada clave se revisa con una operacion atomica, para no perder una solicitud que llegue justo a la vez.
    private static void purgar(Map<String, Deque<Instant>> solicitudes, Instant ahora, Duration ventana) {
        for (String clave : solicitudes.keySet()) {
            solicitudes.computeIfPresent(clave, (k, cola) ->
                    cola.isEmpty() || haSalidoDeLaVentana(cola.peekLast(), ahora, ventana) ? null : cola);
        }
    }
}
