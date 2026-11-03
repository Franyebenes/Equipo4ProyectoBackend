package com.esibuy.esibuy_backend.servicio;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
            Deque<Instant> vigentes = VentanasDeslizantes.vigentes(solicitudes, ahora, propiedades.ventanaIp());
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
            Deque<Instant> vigentes = VentanasDeslizantes.vigentes(solicitudes, ahora, propiedades.ventanaCuenta());
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

    private void purgarSiToca(Instant ahora) {
        Instant anterior = ultimaPurga.get();
        if (ahora.isBefore(anterior.plus(PERIODO_PURGA)) || !ultimaPurga.compareAndSet(anterior, ahora)) {
            return;
        }
        VentanasDeslizantes.purgar(solicitudesPorCuenta, ahora, propiedades.ventanaCuenta());
        VentanasDeslizantes.purgar(solicitudesPorIp, ahora, propiedades.ventanaIp());
    }
}
