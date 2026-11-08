package com.esibuy.esibuy_backend.servicio;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/*
 * Limite de fallos al operar con el segundo factor, en memoria y con ventana deslizante. Un codigo de 6 digitos tiene un
 * millon de valores: sin limite se podria adivinar cuando ya se conoce la contrasena o se tiene una sesion abierta.
 * Valores por defecto: 10 fallos por cuenta en 15 minutos y 30 por IP en una hora.
 *
 * Cuentan los FALLOS (contrasena o codigo incorrectos), no los intentos correctos. Consultar (permitido) no registra
 * nada; registrar es aparte. Un acierto limpia los fallos de la cuenta, no los de la IP (una IP que falla con muchas
 * cuentas sigue siendo sospechosa aunque acierte con una). Cada actualizacion es una operacion atomica por clave y como
 * mucho una vez por minuto se olvidan las claves cuyo ultimo fallo ya salio de la ventana. Es independiente del bloqueo del
 * login y de los limites de la recuperacion de contrasena. Los contadores viven en la memoria de esta instancia.
 * Tests: LimitadorDosFactoresTest (CP-2FA-167 a CP-2FA-178).
 */
public class LimitadorDosFactores {

    private static final String DESCONOCIDO = "(desconocido)";
    private static final Duration PERIODO_PURGA = Duration.ofMinutes(1);

    private final PropiedadesDosFactores propiedades;
    private final Clock reloj;
    private final Map<String, Deque<Instant>> fallosPorCuenta = new ConcurrentHashMap<>();
    private final Map<String, Deque<Instant>> fallosPorIp = new ConcurrentHashMap<>();
    private final AtomicReference<Instant> ultimaPurga;

    public LimitadorDosFactores(PropiedadesDosFactores propiedades, Clock reloj) {
        this.propiedades = propiedades;
        this.reloj = reloj;
        this.ultimaPurga = new AtomicReference<>(reloj.instant());
    }

    //True si ni la cuenta ni la IP han agotado sus fallos. No registra nada.
    public boolean permitido(String idUsuario, String ip) {
        Instant ahora = reloj.instant();
        purgarSiToca(ahora);
        return fallosVigentes(fallosPorCuenta, clave(idUsuario), ahora, propiedades.ventanaCuenta())
                < propiedades.maxFallosCuenta()
                && fallosVigentes(fallosPorIp, clave(ip), ahora, propiedades.ventanaIp()) < propiedades.maxPorIp();
    }

    ///Anota un fallo de la cuenta y de la IP.
    public void registrarFallo(String idUsuario, String ip) {
        Instant ahora = reloj.instant();
        purgarSiToca(ahora);
        anotar(fallosPorCuenta, clave(idUsuario), ahora, propiedades.ventanaCuenta());
        anotar(fallosPorIp, clave(ip), ahora, propiedades.ventanaIp());
    }

    /** Una operacion correcta: se olvidan los fallos de la cuenta (los de la IP no). */
    public void registrarExito(String idUsuario) {
        fallosPorCuenta.remove(clave(idUsuario));
    }

    // Para los tests: cuantas claves (cuentas e IP) sigue recordando.
    int clavesSeguidas() {
        return fallosPorCuenta.size() + fallosPorIp.size();
    }

    private static String clave(String valor) {
        return valor == null || valor.isBlank() ? DESCONOCIDO : valor.strip();
    }

    private static int fallosVigentes(Map<String, Deque<Instant>> fallos, String clave, Instant ahora, Duration ventana) {
        Deque<Instant> cola = fallos.get(clave);
        if (cola == null) {
            return 0;
        }
        int[] vigentes = new int[1];
        // Se cuenta dentro de una operacion atomica sobre la clave para no leer a medias una actualizacion
        fallos.computeIfPresent(clave, (k, actual) -> {
            Deque<Instant> recortada = VentanasDeslizantes.vigentes(actual, ahora, ventana);
            vigentes[0] = recortada.size();
            return recortada;
        });
        return vigentes[0];
    }

    private static void anotar(Map<String, Deque<Instant>> fallos, String clave, Instant ahora, Duration ventana) {
        fallos.compute(clave, (k, actual) -> {
            Deque<Instant> vigentes = VentanasDeslizantes.vigentes(actual, ahora, ventana);
            vigentes.addLast(ahora);
            return vigentes;
        });
    }

    private void purgarSiToca(Instant ahora) {
        Instant anterior = ultimaPurga.get();
        if (ahora.isBefore(anterior.plus(PERIODO_PURGA)) || !ultimaPurga.compareAndSet(anterior, ahora)) {
            return;
        }
        VentanasDeslizantes.purgar(fallosPorCuenta, ahora, propiedades.ventanaCuenta());
        VentanasDeslizantes.purgar(fallosPorIp, ahora, propiedades.ventanaIp());
    }
}
