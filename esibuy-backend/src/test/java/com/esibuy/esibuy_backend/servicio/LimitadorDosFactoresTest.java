package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.esibuy.esibuy_backend.util.PropiedadesDosFactoresPrueba;
import com.esibuy.esibuy_backend.util.RelojMutable;

/**
 * Limite de fallos al verificar el segundo factor (HdU 2FA, fase 2). Casos: CP-2FA-167 a CP-2FA-178.
 *
 * Un codigo de 6 digitos tiene un millon de valores: sin limite se podria adivinar por fuerza bruta cuando ya se conoce
 * la contrasena (o se tiene una sesion abierta). Valores acordados: 10 fallos por cuenta en 15 minutos y 30 fallos por IP
 * en una hora, en ventana deslizante. Cuentan los FALLOS (contrasena o codigo incorrectos al operar con el 2FA), no los
 * intentos correctos. Comprobar (permitido) no registra nada; registrar es aparte. Un acierto limpia los fallos de la
 * cuenta, no los de la IP. Como el resto de limitadores: atomico entre hilos, olvida lo que caduca, en memoria de esta
 * instancia e independiente del bloqueo del login y de los limites de la recuperacion de contrasena.
 *
 * Terminada: ya no lleva la etiqueta pendiente-2fa y entra en la regresion.
 */
class LimitadorDosFactoresTest {

    private static final String CUENTA = "665f1c2e9b1e8a3d4c5b6a79";
    private static final String OTRA_CUENTA = "665f1c2e9b1e8a3d4c5b6a80";
    private static final String IP = "203.0.113.7";

    private RelojMutable reloj;

    @BeforeEach
    void prepararReloj() {
        reloj = new RelojMutable(Instant.parse("2026-10-20T10:00:00Z"), ZoneId.of("Europe/Madrid"));
    }

    private LimitadorDosFactores limitador(int maxFallosCuenta, int maxPorIp) {
        return new LimitadorDosFactores(PropiedadesDosFactoresPrueba.conLimites(maxFallosCuenta, maxPorIp), reloj);
    }

    private static void fallar(LimitadorDosFactores limitador, String cuenta, String ip, int veces) {
        for (int i = 0; i < veces; i++) {
            limitador.registrarFallo(cuenta, ip);
        }
    }

    @Test
    void permitido_unaCuentaYUnaIpSinFallos_esVerdadero() { // CP-2FA-167
        assertThat(limitador(10, 30).permitido(CUENTA, IP)).isTrue();
    }

    @Test
    void permitido_hastaNueveFallosSePermite_ConDiezYaNo() { // CP-2FA-168
        LimitadorDosFactores limitador = limitador(10, 1000);

        fallar(limitador, CUENTA, IP, 9);
        assertThat(limitador.permitido(CUENTA, IP)).isTrue();

        limitador.registrarFallo(CUENTA, IP);
        assertThat(limitador.permitido(CUENTA, IP)).isFalse();
    }

    @Test
    void permitido_losFallosDeUnaCuentaNoBloqueanAOtra() { // CP-2FA-169
        LimitadorDosFactores limitador = limitador(10, 1000);

        fallar(limitador, CUENTA, IP, 10);

        assertThat(limitador.permitido(CUENTA, IP)).isFalse();
        assertThat(limitador.permitido(OTRA_CUENTA, "198.51.100.9")).isTrue();
        // ni siquiera desde la misma IP, mientras esta no haya agotado su propio cupo
        assertThat(limitador.permitido(OTRA_CUENTA, IP)).isTrue();
    }

    @Test
    void permitido_vuelveAPermitirCuandoElFalloMasAntiguoSaleDeLaVentanaDe15Minutos() { // CP-2FA-170
        LimitadorDosFactores limitador = limitador(10, 1000);
        fallar(limitador, CUENTA, IP, 10);

        reloj.avanzar(Duration.ofMinutes(14).plusSeconds(59));
        assertThat(limitador.permitido(CUENTA, IP)).isFalse();

        // A los 15 minutos exactos el fallo ya no cuenta
        reloj.avanzar(Duration.ofSeconds(1));
        assertThat(limitador.permitido(CUENTA, IP)).isTrue();
    }

    @Test
    void permitido_laVentanaEsDeslizante_noSeVaciaDeGolpe() { // CP-2FA-171
        LimitadorDosFactores limitador = limitador(10, 1000);
        fallar(limitador, CUENTA, IP, 5); // t = 0
        reloj.avanzar(Duration.ofMinutes(10));
        fallar(limitador, CUENTA, IP, 5); // t = 10 min
        assertThat(limitador.permitido(CUENTA, IP)).isFalse();

        // A los 15 min salen los cinco primeros y quedan cinco: se vuelve a permitir, pero no hay cupo entero
        reloj.avanzar(Duration.ofMinutes(5));
        assertThat(limitador.permitido(CUENTA, IP)).isTrue();
        fallar(limitador, CUENTA, IP, 5);
        assertThat(limitador.permitido(CUENTA, IP)).isFalse();
    }

    @Test
    void registrarExito_limpiaLosFallosDeLaCuentaPeroNoLosDeLaIp() { // CP-2FA-172
        LimitadorDosFactores limitador = limitador(10, 12);
        fallar(limitador, CUENTA, IP, 10);
        assertThat(limitador.permitido(CUENTA, IP)).isFalse();

        limitador.registrarExito(CUENTA);

        // La cuenta queda limpia, pero la IP lleva 10 fallos de 12
        assertThat(limitador.permitido(CUENTA, "198.51.100.9")).isTrue();
        assertThat(limitador.permitido(CUENTA, IP)).isTrue();
        fallar(limitador, OTRA_CUENTA, IP, 2);
        assertThat(limitador.permitido(CUENTA, IP)).isFalse();
    }

    @Test
    void permitido_treintaFallosDeUnaIpRepartidosEntreCuentas_laBloqueanParaTodas() { // CP-2FA-173
        LimitadorDosFactores limitador = limitador(1000, 30);
        for (int i = 0; i < 30; i++) {
            limitador.registrarFallo("cuenta-" + i, IP);
        }

        assertThat(limitador.permitido("una-cuenta-sin-fallos", IP)).isFalse();
    }

    @Test
    void permitido_laIpVuelveAPermitirseTrasUnaHora() { // CP-2FA-174
        LimitadorDosFactores limitador = limitador(1000, 30);
        for (int i = 0; i < 30; i++) {
            limitador.registrarFallo("cuenta-" + i, IP);
        }

        reloj.avanzar(Duration.ofMinutes(59).plusSeconds(59));
        assertThat(limitador.permitido(CUENTA, IP)).isFalse();
        reloj.avanzar(Duration.ofSeconds(1));
        assertThat(limitador.permitido(CUENTA, IP)).isTrue();
    }

    @Test
    void permitido_losFallosDeUnaIpNoBloqueanOtrasIp() { // CP-2FA-175
        LimitadorDosFactores limitador = limitador(1000, 30);
        for (int i = 0; i < 30; i++) {
            limitador.registrarFallo("cuenta-" + i, IP);
        }

        assertThat(limitador.permitido(CUENTA, "198.51.100.9")).isTrue();
    }

    @Test
    void permitido_consultarNoRegistraNada() { // CP-2FA-176
        LimitadorDosFactores limitador = limitador(10, 30);

        for (int i = 0; i < 500; i++) {
            assertThat(limitador.permitido(CUENTA, IP)).isTrue();
        }
        assertThat(limitador.clavesSeguidas()).isZero();
    }

    @Test
    void registrarFallo_desdeVariosHilos_noPierdeNingunoYNoSePasaDeLaCuenta() throws Exception { // CP-2FA-177
        // Nueve fallos simultaneos: ninguno se pierde, asi que todavia hay uno de cupo; el decimo cierra la cuenta
        LimitadorDosFactores limitador = limitador(10, 1000);
        ExecutorService ejecutor = Executors.newFixedThreadPool(9);
        try {
            CyclicBarrier barrera = new CyclicBarrier(9);
            List<Future<?>> futuros = new ArrayList<>();
            for (int i = 0; i < 9; i++) {
                futuros.add(ejecutor.submit(() -> {
                    barrera.await(10, TimeUnit.SECONDS);
                    limitador.registrarFallo(CUENTA, IP);
                    return null;
                }));
            }
            for (Future<?> futuro : futuros) {
                futuro.get(30, TimeUnit.SECONDS);
            }
        } finally {
            ejecutor.shutdownNow();
        }

        assertThat(limitador.permitido(CUENTA, IP)).isTrue();
        limitador.registrarFallo(CUENTA, IP);
        assertThat(limitador.permitido(CUENTA, IP)).isFalse();
    }

    @Test
    void sinCuentaOSinIp_seTrataComoDesconocidaYNoLanzaExcepcion() { // CP-2FA-178
        LimitadorDosFactores limitador = limitador(10, 1000);

        assertThat(limitador.permitido(null, null)).isTrue();
        fallar(limitador, null, null, 10);

        assertThat(limitador.permitido(null, null)).isFalse();
        assertThat(limitador.permitido(" ", "  ")).isFalse(); // en blanco es lo mismo que nulo
        limitador.registrarExito(null);
        assertThat(limitador.permitido(null, null)).isTrue();
    }

    @Test
    void sigueSoloLasClavesVigentes_olvidaLasQueHanCaducado() { // CP-2FA-178
        LimitadorDosFactores limitador = limitador(10, 30);
        limitador.registrarFallo("cuenta-1", "203.0.113.1");
        limitador.registrarFallo("cuenta-2", "203.0.113.2");
        limitador.registrarFallo("cuenta-3", "203.0.113.3");
        assertThat(limitador.clavesSeguidas()).isEqualTo(6); // tres cuentas y tres IP

        reloj.avanzar(Duration.ofHours(2));
        limitador.permitido("otra", "203.0.113.9"); // una operacion cualquiera dispara la purga periodica

        assertThat(limitador.clavesSeguidas()).isZero();
    }
}
