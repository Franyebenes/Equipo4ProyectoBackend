package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.esibuy.esibuy_backend.util.RelojMutable;

/**
 * Limites de la recuperacion de contrasena en memoria (HdU recuperacion de contrasena, fase 3).
 * Casos: CP-LIM-10 a CP-LIM-29.
 *
 * Valores acordados: 5 solicitudes por cuenta en 24 h, 60 s de espera entre dos solicitudes de la misma cuenta y 10
 * solicitudes por hora por IP, con ventanas deslizantes. El reloj es manual, asi que ningun test espera tiempo real.
 *
 * Reglas que fijan:
 *  - Solo las solicitudes permitidas quedan registradas: insistir mientras se esta limitado no alarga el castigo.
 *  - Una solicitud permitida a partir del instante exacto en que caduca la anterior (cooldown y ventana).
 *  - Si una cuenta incumple a la vez la espera y el maximo diario, se informa del maximo diario.
 *  - Las cuentas son independientes entre si, las IP tambien, y las cuentas y las IP no se influyen.
 *  - La comprobacion y el registro son atomicos: con peticiones simultaneas nunca se pasa del cupo.
 *  - Para no acumular memoria, las claves cuyo ultimo registro ya salio de todas las ventanas se olvidan.
 *  - No depende de ningun componente del login: su bloqueo y este limite son independientes.
 *
 * Terminada: ya no lleva la etiqueta pendiente-recuperacion y entra en la regresion.
 */
class LimitadorRecuperacionEnMemoriaTest {

    private static final Duration DIA = Duration.ofHours(24);
    private static final Duration COOLDOWN = Duration.ofSeconds(60);
    private static final Duration HORA = Duration.ofHours(1);
    private static final String CUENTA = "ana@ejemplo.es";
    private static final String IP = "203.0.113.7";

    private RelojMutable reloj;
    private LimitadorRecuperacionEnMemoria limitador;

    @BeforeEach
    void prepararLimitador() {
        reloj = new RelojMutable(Instant.parse("2026-10-17T10:00:00Z"), ZoneId.of("Europe/Madrid"));
        limitador = new LimitadorRecuperacionEnMemoria(new PropiedadesRecuperacion(5, DIA, COOLDOWN, 10, HORA), reloj);
    }

    private Optional<MotivoLimiteRecuperacion> cuenta(String correo) {
        return limitador.registrarSolicitudDeCuenta(correo);
    }

    private Optional<MotivoLimiteRecuperacion> ip(String direccion) {
        return limitador.registrarSolicitudDeIp(direccion);
    }

    /** Hace n solicitudes de la cuenta separadas por el tiempo de espera, y comprueba que todas se permiten. */
    private void solicitarSeparadasPorElCooldown(String correo, int n) {
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                reloj.avanzar(COOLDOWN);
            }
            assertThat(cuenta(correo)).as("solicitud %d", i + 1).isEmpty();
        }
    }

    // ------------------------------------------------------------------ cuenta: espera entre solicitudes

    @Test
    void cuenta_primeraSolicitud_sePermite() { // CP-LIM-10
        assertThat(cuenta(CUENTA)).isEmpty();
    }

    @Test
    void cuenta_segundaSolicitudInmediata_noSePermitePorLaEspera() { // CP-LIM-11
        cuenta(CUENTA);

        assertThat(cuenta(CUENTA)).contains(MotivoLimiteRecuperacion.COOLDOWN);
    }

    @Test
    void cuenta_laEsperaTerminaExactamenteALos60Segundos() { // CP-LIM-12
        cuenta(CUENTA);

        reloj.avanzar(COOLDOWN.minusSeconds(1));
        assertThat(cuenta(CUENTA)).contains(MotivoLimiteRecuperacion.COOLDOWN);

        reloj.avanzar(Duration.ofSeconds(1));
        assertThat(cuenta(CUENTA)).isEmpty();
    }

    @Test
    void cuenta_insistirDuranteLaEspera_noLaAlarga() { // CP-LIM-13
        // Given: una solicitud permitida y varios intentos rechazados durante la espera
        cuenta(CUENTA);
        for (int i = 0; i < 5; i++) {
            reloj.avanzar(Duration.ofSeconds(10));
            assertThat(cuenta(CUENTA)).contains(MotivoLimiteRecuperacion.COOLDOWN);
        }

        // When: pasan los 60 s desde la unica solicitud permitida (5 x 10 s = 50 s, mas 10 s)
        reloj.avanzar(Duration.ofSeconds(10));

        // Then: se permite; los intentos rechazados no movieron el reloj de la espera
        assertThat(cuenta(CUENTA)).isEmpty();
    }

    // ------------------------------------------------------------------ cuenta: maximo diario

    @Test
    void cuenta_cincoSolicitudesSeparadasPorLaEspera_sePermitenYLaSextaNo() { // CP-LIM-14
        solicitarSeparadasPorElCooldown(CUENTA, 5);

        reloj.avanzar(COOLDOWN);
        assertThat(cuenta(CUENTA)).contains(MotivoLimiteRecuperacion.LIMITE_DIARIO);
    }

    @Test
    void cuenta_superadoElMaximoDiario_vuelveAPermitirCuandoLaPrimeraSaleDeLaVentana() { // CP-LIM-15
        // Given: 5 solicitudes a las 10:00, 10:01, ... 10:04; la primera fue a las 10:00
        solicitarSeparadasPorElCooldown(CUENTA, 5);
        Instant primera = Instant.parse("2026-10-17T10:00:00Z");

        // When / Then: un segundo antes de cumplirse las 24 h de la primera sigue limitada...
        reloj.avanzar(Duration.between(reloj.instant(), primera.plus(DIA).minusSeconds(1)));
        assertThat(cuenta(CUENTA)).contains(MotivoLimiteRecuperacion.LIMITE_DIARIO);

        // ...y en el instante exacto en que cumple las 24 h, la primera sale de la ventana y se permite
        reloj.avanzar(Duration.ofSeconds(1));
        assertThat(cuenta(CUENTA)).isEmpty();
    }

    @Test
    void cuenta_laVentanaEsDeslizante_noSeReiniciaAMedianoche() { // CP-LIM-16
        // Given: 5 solicitudes seguidas un poco antes de las 24:00
        reloj = new RelojMutable(Instant.parse("2026-10-17T21:55:00Z"), ZoneId.of("Europe/Madrid")); // 23:55 en Madrid
        limitador = new LimitadorRecuperacionEnMemoria(new PropiedadesRecuperacion(5, DIA, COOLDOWN, 10, HORA), reloj);
        solicitarSeparadasPorElCooldown(CUENTA, 5);

        // When: pasa la medianoche (menos de una hora despues)
        reloj.avanzar(Duration.ofMinutes(30));

        // Then: sigue limitada (un reinicio a medianoche permitiria 10 solicitudes en media hora)
        assertThat(cuenta(CUENTA)).contains(MotivoLimiteRecuperacion.LIMITE_DIARIO);
    }

    @Test
    void cuenta_incumpleLaEsperaYElMaximoDiario_seInformaDelMaximoDiario() { // CP-LIM-17
        // Given: 5 solicitudes y, sin esperar, una sexta
        solicitarSeparadasPorElCooldown(CUENTA, 5);

        // When / Then
        assertThat(cuenta(CUENTA)).contains(MotivoLimiteRecuperacion.LIMITE_DIARIO);
    }

    @Test
    void cuenta_unMaximoDistintoDeCinco_seRespeta() { // CP-LIM-18
        limitador = new LimitadorRecuperacionEnMemoria(new PropiedadesRecuperacion(2, DIA, COOLDOWN, 10, HORA), reloj);

        solicitarSeparadasPorElCooldown(CUENTA, 2);
        reloj.avanzar(COOLDOWN);

        assertThat(cuenta(CUENTA)).contains(MotivoLimiteRecuperacion.LIMITE_DIARIO);
    }

    // ------------------------------------------------------------------ cuenta: independencia y entrada

    @Test
    void cuenta_cuentasDistintas_tienenContadoresIndependientes() { // CP-LIM-19
        cuenta(CUENTA);

        assertThat(cuenta(CUENTA)).contains(MotivoLimiteRecuperacion.COOLDOWN);
        assertThat(cuenta("luis@ejemplo.es")).isEmpty();
    }

    @ParameterizedTest(name = "correo no valido: [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = { "  " })
    void cuenta_correoNuloOVacio_seRechaza(String correo) { // CP-LIM-20
        assertThrows(IllegalArgumentException.class, () -> cuenta(correo));
    }

    // ------------------------------------------------------------------ IP

    @Test
    void ip_diezSolicitudesSePermitenYLaOnceNo() { // CP-LIM-21
        for (int i = 1; i <= 10; i++) {
            assertThat(ip(IP)).as("solicitud %d", i).isEmpty();
        }

        assertThat(ip(IP)).contains(MotivoLimiteRecuperacion.IP_AGOTADA);
    }

    @Test
    void ip_vuelveAPermitirCuandoLaMasAntiguaSaleDeLaVentanaDeUnaHora() { // CP-LIM-22
        // Given: 10 solicitudes, una cada minuto (la primera a las 10:00)
        for (int i = 0; i < 10; i++) {
            if (i > 0) {
                reloj.avanzar(Duration.ofMinutes(1));
            }
            ip(IP);
        }
        assertThat(ip(IP)).contains(MotivoLimiteRecuperacion.IP_AGOTADA);

        // When: llega el instante exacto en que la primera cumple la hora (11:00); estamos en las 10:09
        reloj.avanzar(Duration.ofMinutes(51));

        // Then: se libera un hueco, solo uno
        assertThat(ip(IP)).isEmpty();
        assertThat(ip(IP)).contains(MotivoLimiteRecuperacion.IP_AGOTADA);
    }

    @Test
    void ip_insistirCuandoEstaAgotada_noAlargaElCastigo() { // CP-LIM-23
        for (int i = 0; i < 10; i++) {
            ip(IP);
        }
        for (int i = 0; i < 20; i++) {
            reloj.avanzar(Duration.ofMinutes(2));
            ip(IP);
        }

        // 40 minutos de insistencia + 20 min mas = 60 min desde las 10 solicitudes (todas a las 10:00)
        reloj.avanzar(Duration.ofMinutes(20));
        assertThat(ip(IP)).isEmpty();
    }

    @Test
    void ip_ipsDistintas_tienenContadoresIndependientes() { // CP-LIM-24
        for (int i = 0; i < 10; i++) {
            ip(IP);
        }

        assertThat(ip(IP)).contains(MotivoLimiteRecuperacion.IP_AGOTADA);
        assertThat(ip("198.51.100.9")).isEmpty();
    }

    @Test
    void ip_ipNulaOVacia_cuentaComoUnaUnicaIpDesconocidaSinFallar() { // CP-LIM-25
        for (int i = 0; i < 5; i++) {
            assertThat(ip(null)).isEmpty();
            assertThat(ip("  ")).isEmpty();
        }

        // las diez van al mismo cubo: la siguiente se rechaza
        assertThat(ip(null)).contains(MotivoLimiteRecuperacion.IP_AGOTADA);
    }

    @Test
    void ipYCuenta_sonContadoresSeparados() { // CP-LIM-26
        // Agotar la IP no limita a las cuentas...
        for (int i = 0; i < 11; i++) {
            ip(IP);
        }
        assertThat(cuenta(CUENTA)).isEmpty();

        // ...y agotar una cuenta no limita a la IP
        assertThat(cuenta(CUENTA)).contains(MotivoLimiteRecuperacion.COOLDOWN);
        assertThat(ip("198.51.100.9")).isEmpty();
    }

    // ------------------------------------------------------------------ concurrencia

    private <T> List<T> ejecutarALaVez(int hilos, Supplier<T> tarea) throws Exception {
        ExecutorService ejecutor = Executors.newFixedThreadPool(hilos);
        CyclicBarrier salida = new CyclicBarrier(hilos);
        try {
            List<Future<T>> futuros = new ArrayList<>();
            for (int i = 0; i < hilos; i++) {
                futuros.add(ejecutor.submit(() -> {
                    salida.await(5, TimeUnit.SECONDS);
                    return tarea.get();
                }));
            }
            List<T> resultados = new ArrayList<>();
            for (Future<T> futuro : futuros) {
                resultados.add(futuro.get(10, TimeUnit.SECONDS));
            }
            return resultados;
        } finally {
            ejecutor.shutdownNow();
        }
    }

    @Test
    void cuenta_cincuentaSolicitudesSimultaneas_soloUnaPasaPorLaEspera() throws Exception { // CP-LIM-27
        List<Optional<MotivoLimiteRecuperacion>> resultados = ejecutarALaVez(50, () -> cuenta(CUENTA));

        assertThat(resultados.stream().filter(Optional::isEmpty).count()).isEqualTo(1);
    }

    @Test
    void ip_cincuentaSolicitudesSimultaneas_pasanExactamenteDiez() throws Exception { // CP-LIM-28
        List<Optional<MotivoLimiteRecuperacion>> resultados = ejecutarALaVez(50, () -> ip(IP));

        assertThat(resultados.stream().filter(Optional::isEmpty).count()).isEqualTo(10);
    }

    // ------------------------------------------------------------------ memoria

    @Test
    void memoria_lasClavesQueYaSalieronDeTodasLasVentanasSeOlvidan() { // CP-LIM-29
        // Given: muchas cuentas e IP distintas, como en un barrido
        for (int i = 0; i < 100; i++) {
            cuenta("usuario" + i + "@ejemplo.es");
            ip("10.0.0." + i);
        }
        assertThat(limitador.clavesSeguidas()).isEqualTo(200);

        // When: pasa mas de un dia y llega una sola solicitud nueva
        reloj.avanzar(DIA.plusMinutes(1));
        ip("198.51.100.9");

        // Then: solo recuerda la nueva
        assertThat(limitador.clavesSeguidas()).isEqualTo(1);
    }
}
