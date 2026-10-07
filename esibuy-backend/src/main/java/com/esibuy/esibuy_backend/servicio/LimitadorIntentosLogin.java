package com.esibuy.esibuy_backend.servicio;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.esibuy.esibuy_backend.excepcion.LoginBloqueadoTemporalmenteException;

/*
 * Contadores de intentos fallidos, bloqueo progresivo de cuentas y limite de intentos por IP.
 *
 * Por cuenta (correo normalizado): al llegar a 5 fallos seguidos se bloquea 30 s, 2, 4 y 5 min, y se mantiene en
 * 5 min; los fallos separados por mas de 10 min no se acumulan y un exito reinicia contador y nivel.
 *
 * Por IP y usuario: 5 intentos cada 5 min. Si una IP prueba 20 usuarios distintos en 5 min pasa al modo
 * adaptativo durante 5 min: 2 intentos por usuario y un retraso fijo de 2 s por intento, y se avisa a operacion.
 *
 * Los contadores viven en memoria de esta instancia y son seguros entre hilos: cada cuenta y cada IP se
 * modifican bajo su propio cerrojo. El reloj, la pausa y los avisos se inyectan para poder probarlo sin esperas.
 */
@Component
public class LimitadorIntentosLogin {

    private static final int FALLOS_PARA_BLOQUEAR = 5;
    private static final Duration VENTANA_FALLOS = Duration.ofMinutes(10);
    private static final long[] SEGUNDOS_DE_BLOQUEO = {30, 120, 240, 300};

    private static final Duration VENTANA_INTENTOS = Duration.ofMinutes(5);
    private static final int INTENTOS_BASE = 5;
    private static final int INTENTOS_ADAPTATIVO = 2;
    private static final int USUARIOS_PARA_MODO_ADAPTATIVO = 20;
    private static final Duration RETRASO_ADAPTATIVO = Duration.ofSeconds(2);

    private final Clock reloj;
    private final Pausador pausador;
    private final AlertasSeguridad alertas;
    private final Map<String, Cuenta> cuentas = new ConcurrentHashMap<>();
    private final Map<String, VentanaIp> ventanasPorIp = new ConcurrentHashMap<>();

    public LimitadorIntentosLogin(Clock reloj, Pausador pausador, AlertasSeguridad alertas) {
        this.reloj = reloj;
        this.pausador = pausador;
        this.alertas = alertas;
    }

    /*
     * Puerta de cada intento de inicio de sesion. Rechaza si la cuenta esta bloqueada o si esa IP ha agotado sus
     * intentos para ese usuario; un intento rechazado no cuenta. En modo adaptativo espera 2 s antes de dejar pasar.
     */
    public void comprobarIntento(String correo, String ip) {
        String clave = NormalizadorRegistro.email(correo);
        Instant ahora = reloj.instant();

        Cuenta cuenta = cuentas.get(clave);
        if (cuenta != null) {
            long segundosDeBloqueo = cuenta.segundosDeBloqueo(ahora);
            if (segundosDeBloqueo > 0) {
                throw new LoginBloqueadoTemporalmenteException(segundosDeBloqueo);
            }
        }

        Admision admision = ventanasPorIp.computeIfAbsent(ip, direccion -> new VentanaIp()).registrar(clave, ahora);
        if (admision.activadoAhora()) {
            alertas.ipEnModoAdaptativo(ip, admision.usuariosDistintos());
        }
        if (admision.enModoAdaptativo()) {
            pausador.pausar(RETRASO_ADAPTATIVO);
        }
    }

    /*
     * Suma un fallo a la cuenta, venga de la IP que venga: el contador es de la cuenta y el limite por IP se cuenta
     * en comprobarIntento. La IP solo se usa para avisar si este fallo activa un bloqueo.
     */
    public void registrarFallo(String correo, String ip) {
        String clave = NormalizadorRegistro.email(correo);
        long segundosDeBloqueo = cuentas.computeIfAbsent(clave, c -> new Cuenta()).registrarFallo(reloj.instant());
        if (segundosDeBloqueo > 0) {
            alertas.cuentaBloqueada(clave, ip, segundosDeBloqueo);
        }
    }

    // Un inicio de sesion correcto reinicia el contador de fallos y el nivel de escalado de la cuenta.
    public void registrarExito(String correo) {
        cuentas.remove(NormalizadorRegistro.email(correo));
    }

    // Fallos acumulados de la cuenta (0 si no tiene).
    public int fallosConsecutivos(String correo) {
        Cuenta cuenta = cuentas.get(NormalizadorRegistro.email(correo));
        return cuenta == null ? 0 : cuenta.fallos();
    }

    //Segundos que faltan hasta el instante dado, redondeados hacia arriba y como minimo 1.
    private static long segundosHasta(Instant limite, Instant ahora) {
        Duration restante = Duration.between(ahora, limite);
        long segundos = restante.getSeconds() + (restante.getNano() > 0 ? 1 : 0);
        return Math.max(1, segundos);
    }

    //Estado de bloqueo de una cuenta.
    private static final class Cuenta {
        private int fallos;
        private int nivel;
        private Instant ultimoFallo;
        private Instant bloqueadoHasta;

        // Suma un fallo; devuelve los segundos del bloqueo que ese fallo activa, o 0 si no activa ninguno.
        synchronized long registrarFallo(Instant ahora) {
            if (ultimoFallo != null && Duration.between(ultimoFallo, ahora).compareTo(VENTANA_FALLOS) > 0) {
                fallos = 0;
            }
            fallos++;
            ultimoFallo = ahora;
            // Con el bloqueo vigente los fallos suman pero no escalan
            if (fallos < FALLOS_PARA_BLOQUEAR || segundosDeBloqueo(ahora) > 0) {
                return 0;
            }
            nivel = Math.min(nivel + 1, SEGUNDOS_DE_BLOQUEO.length);
            long segundosDeBloqueo = SEGUNDOS_DE_BLOQUEO[nivel - 1];
            bloqueadoHasta = ahora.plusSeconds(segundosDeBloqueo);
            return segundosDeBloqueo;
        }

        synchronized long segundosDeBloqueo(Instant ahora) {
            return bloqueadoHasta != null && ahora.isBefore(bloqueadoHasta) ? segundosHasta(bloqueadoHasta, ahora) : 0;
        }

        synchronized int fallos() {
            return fallos;
        }
    }

    // Intentos recientes de una IP, con el modo adaptativo si lo tiene activo.
    private static final class VentanaIp {
        private final Deque<Intento> intentos = new ArrayDeque<>();
        private Instant adaptativoHasta;

        synchronized Admision registrar(String usuario, Instant ahora) {
            descartarCaducados(ahora);
            boolean adaptativo = adaptativoHasta != null && ahora.isBefore(adaptativoHasta);
            rechazarSiSuperaElLimite(usuario, adaptativo ? INTENTOS_ADAPTATIVO : INTENTOS_BASE, ahora);

            intentos.addLast(new Intento(usuario, ahora));
            int usuariosDistintos = (int) intentos.stream().map(Intento::usuario).distinct().count();
            boolean activadoAhora = !adaptativo && usuariosDistintos >= USUARIOS_PARA_MODO_ADAPTATIVO;
            if (activadoAhora) {
                adaptativoHasta = ahora.plus(VENTANA_INTENTOS);
            }
            return new Admision(adaptativo || activadoAhora, activadoAhora, usuariosDistintos);
        }

        private void descartarCaducados(Instant ahora) {
            while (!intentos.isEmpty() && !ahora.isBefore(intentos.peekFirst().momento().plus(VENTANA_INTENTOS))) {
                intentos.removeFirst();
            }
        }

        private void rechazarSiSuperaElLimite(String usuario, int limite, Instant ahora) {
            List<Instant> propios = intentos.stream()
                    .filter(intento -> intento.usuario().equals(usuario))
                    .map(Intento::momento)
                    .toList();
            if (propios.size() >= limite) {
                // Podra volver a intentarlo cuando caduque el intento que lo deja por debajo del limite
                Instant libre = propios.get(propios.size() - limite).plus(VENTANA_INTENTOS);
                throw new LoginBloqueadoTemporalmenteException(segundosHasta(libre, ahora));
            }
        }
    }

    private record Intento(String usuario, Instant momento) {
    }

    private record Admision(boolean enModoAdaptativo, boolean activadoAhora, int usuariosDistintos) {
    }
}
