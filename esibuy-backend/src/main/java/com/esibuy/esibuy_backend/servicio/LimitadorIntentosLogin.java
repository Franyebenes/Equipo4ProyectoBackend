package com.esibuy.esibuy_backend.servicio;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

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
 *
 * Para no acumular memoria sin limite, las cuentas que llevan una hora sin actividad (desde su ultimo fallo o desde
 * el final de su ultimo bloqueo) y las IPs sin intentos en su ventana se olvidan. La limpieza ocurre como mucho una
 * vez por minuto, aprovechando las propias peticiones.
 */
@Component
public class LimitadorIntentosLogin {

    private static final int FALLOS_PARA_BLOQUEAR = 5;
    private static final Duration VENTANA_FALLOS = Duration.ofMinutes(10);
    private static final long[] SEGUNDOS_DE_BLOQUEO = {30, 120, 240, 300};
    private static final Duration RETENCION_CUENTA = Duration.ofHours(1);

    private static final Duration VENTANA_INTENTOS = Duration.ofMinutes(5);
    private static final int INTENTOS_BASE = 5;
    private static final int INTENTOS_ADAPTATIVO = 2;
    private static final int USUARIOS_PARA_MODO_ADAPTATIVO = 20;
    private static final Duration RETRASO_ADAPTATIVO = Duration.ofSeconds(2);

    private static final Duration PERIODO_PURGA = Duration.ofMinutes(1);

    private final Clock reloj;
    private final Pausador pausador;
    private final AlertasSeguridad alertas;
    private final Map<String, Cuenta> cuentas = new ConcurrentHashMap<>();
    private final Map<String, VentanaIp> ventanasPorIp = new ConcurrentHashMap<>();
    private final AtomicReference<Instant> ultimaPurga;

    public LimitadorIntentosLogin(Clock reloj, Pausador pausador, AlertasSeguridad alertas) {
        this.reloj = reloj;
        this.pausador = pausador;
        this.alertas = alertas;
        this.ultimaPurga = new AtomicReference<>(reloj.instant());
    }

    /*
     * Puerta de cada intento de inicio de sesion. Rechaza si la cuenta esta bloqueada o si esa IP ha agotado sus
     * intentos para ese usuario; un intento rechazado no cuenta. En modo adaptativo espera 2 s antes de dejar pasar.
     */
    public void comprobarIntento(String correo, String ip) {
        String clave = Normalizador.email(correo);
        Instant ahora = reloj.instant();
        purgarSiToca(ahora);

        Cuenta cuenta = cuentas.get(clave);
        if (cuenta != null) {
            long segundosDeBloqueo = cuenta.segundosDeBloqueo(ahora);
            if (segundosDeBloqueo > 0) {
                throw new LoginBloqueadoTemporalmenteException(segundosDeBloqueo);
            }
        }

        Admision admision = registrarIntento(ip, clave, ahora);
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
        String clave = Normalizador.email(correo);
        Instant ahora = reloj.instant();
        purgarSiToca(ahora);

        // Dentro de compute, para que la limpieza no pueda retirar la cuenta justo mientras se le suma el fallo
        long[] segundosDeBloqueo = new long[1];
        cuentas.compute(clave, (k, existente) -> {
            Cuenta cuenta = existente != null ? existente : new Cuenta();
            segundosDeBloqueo[0] = cuenta.registrarFallo(ahora);
            return cuenta;
        });
        if (segundosDeBloqueo[0] > 0) {
            alertas.cuentaBloqueada(clave, ip, segundosDeBloqueo[0]);
        }
    }

    // Un inicio de sesion correcto reinicia el contador de fallos y el nivel de escalado de la cuenta.
    public void registrarExito(String correo) {
        cuentas.remove(Normalizador.email(correo));
    }

    // Fallos acumulados de la cuenta (0 si no tiene). Solo lo usan las pruebas, por eso es de paquete.
    int fallosConsecutivos(String correo) {
        Cuenta cuenta = cuentas.get(Normalizador.email(correo));
        return cuenta == null ? 0 : cuenta.fallos();
    }

    // Cuentas e IPs que el limitador recuerda ahora mismo. Solo para las pruebas de la purga de memoria.
    int cuentasEnMemoria() {
        return cuentas.size();
    }

    int ipsEnMemoria() {
        return ventanasPorIp.size();
    }

    // Anota el intento en la ventana de la IP. Si supera el limite lanza la excepcion y no se guarda nada.
    private Admision registrarIntento(String ip, String usuario, Instant ahora) {
        Admision[] admision = new Admision[1];
        ventanasPorIp.compute(ip, (k, existente) -> {
            VentanaIp ventana = existente != null ? existente : new VentanaIp();
            admision[0] = ventana.registrar(usuario, ahora);
            return ventana;
        });
        return admision[0];
    }

    // Olvida las cuentas y las IPs sin actividad. Solo una peticion por periodo se encarga de hacerlo.
    private void purgarSiToca(Instant ahora) {
        Instant anterior = ultimaPurga.get();
        if (Duration.between(anterior, ahora).compareTo(PERIODO_PURGA) < 0
                || !ultimaPurga.compareAndSet(anterior, ahora)) {
            return;
        }
        for (String clave : cuentas.keySet()) {
            cuentas.computeIfPresent(clave, (k, cuenta) -> cuenta.olvidable(ahora) ? null : cuenta);
        }
        for (String ip : ventanasPorIp.keySet()) {
            ventanasPorIp.computeIfPresent(ip, (k, ventana) -> ventana.sinActividad(ahora) ? null : ventana);
        }
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

        // Una hora despues de su ultimo fallo o del final de su ultimo bloqueo, lo que ocurra mas tarde.
        synchronized boolean olvidable(Instant ahora) {
            Instant ultimaActividad = bloqueadoHasta != null && bloqueadoHasta.isAfter(ultimoFallo)
                    ? bloqueadoHasta
                    : ultimoFallo;
            return !ahora.isBefore(ultimaActividad.plus(RETENCION_CUENTA));
        }
    }

    /*
     * Intentos recientes de una IP, con el modo adaptativo si lo tiene activo. Ademas del orden cronologico (para
     * descartar los caducados) guarda los instantes de cada usuario: asi saber cuantos usuarios distintos hay y
     * cuantos intentos lleva cada uno no exige recorrer todos los intentos de la IP en cada peticion.
     */
    private static final class VentanaIp {
        private final Deque<Intento> cronologico = new ArrayDeque<>();
        private final Map<String, Deque<Instant>> porUsuario = new HashMap<>();
        private Instant adaptativoHasta;

        synchronized Admision registrar(String usuario, Instant ahora) {
            descartarCaducados(ahora);
            boolean adaptativo = estaEnModoAdaptativo(ahora);
            rechazarSiSuperaElLimite(usuario, adaptativo ? INTENTOS_ADAPTATIVO : INTENTOS_BASE, ahora);

            cronologico.addLast(new Intento(usuario, ahora));
            porUsuario.computeIfAbsent(usuario, u -> new ArrayDeque<>()).addLast(ahora);
            int usuariosDistintos = porUsuario.size();
            boolean activadoAhora = !adaptativo && usuariosDistintos >= USUARIOS_PARA_MODO_ADAPTATIVO;
            if (activadoAhora) {
                adaptativoHasta = ahora.plus(VENTANA_INTENTOS);
            }
            return new Admision(adaptativo || activadoAhora, activadoAhora, usuariosDistintos);
        }

        // Ya no tiene intentos en su ventana ni modo adaptativo vigente: se puede olvidar.
        synchronized boolean sinActividad(Instant ahora) {
            descartarCaducados(ahora);
            return cronologico.isEmpty() && !estaEnModoAdaptativo(ahora);
        }

        private boolean estaEnModoAdaptativo(Instant ahora) {
            return adaptativoHasta != null && ahora.isBefore(adaptativoHasta);
        }

        private void descartarCaducados(Instant ahora) {
            while (!cronologico.isEmpty() && !ahora.isBefore(cronologico.peekFirst().momento().plus(VENTANA_INTENTOS))) {
                Intento caducado = cronologico.removeFirst();
                // Es el mas antiguo de su usuario: los intentos de cada usuario estan en el mismo orden
                Deque<Instant> propios = porUsuario.get(caducado.usuario());
                propios.removeFirst();
                if (propios.isEmpty()) {
                    porUsuario.remove(caducado.usuario());
                }
            }
        }

        private void rechazarSiSuperaElLimite(String usuario, int limite, Instant ahora) {
            Deque<Instant> propios = porUsuario.get(usuario);
            if (propios != null && propios.size() >= limite) {
                // Podra volver a intentarlo cuando caduque el intento que lo deja por debajo del limite
                Instant libre = elemento(propios, propios.size() - limite).plus(VENTANA_INTENTOS);
                throw new LoginBloqueadoTemporalmenteException(segundosHasta(libre, ahora));
            }
        }

        // El elemento en esa posicion (como mucho 5 intentos por usuario, asi que el recorrido es minimo).
        private static Instant elemento(Deque<Instant> instantes, int indice) {
            Iterator<Instant> iterador = instantes.iterator();
            for (int i = 0; i < indice; i++) {
                iterador.next();
            }
            return iterador.next();
        }
    }

    private record Intento(String usuario, Instant momento) {
    }

    private record Admision(boolean enModoAdaptativo, boolean activadoAhora, int usuariosDistintos) {
    }
}
