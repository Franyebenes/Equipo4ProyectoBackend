package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.esibuy.esibuy_backend.util.RelojMutable;

/**
 * Limite de intentos de restablecer la contrasena por IP (HdU recuperacion de contrasena, fase 4).
 * Casos: CP-RST-20 a CP-RST-28.
 *
 * Valores acordados: 10 intentos por hora por IP, en ventana deslizante. Como el token tiene 256 bits no se puede
 * adivinar, pero cada intento con un token valido cuesta una consulta a Pwned Passwords y un hash Argon2id, y el limite
 * evita que alguien lo use para castigar al servidor o a ese servicio externo. Mismas reglas que el limite de
 * solicitudes de la fase 3: solo se registran los intentos permitidos, atomico entre hilos, olvida lo que caduca.
 * No depende del limitador del login ni del de solicitudes.
 *
 * Terminada: ya no lleva la etiqueta pendiente-recuperacion y entra en la regresion.
 */
class LimitadorRestablecimientoTest {

    private static final Duration HORA = Duration.ofHours(1);
    private static final String IP = "203.0.113.7";

    private RelojMutable reloj;
    private LimitadorRestablecimiento limitador;

    @BeforeEach
    void prepararLimitador() {
        reloj = new RelojMutable(Instant.parse("2026-10-19T10:00:00Z"), ZoneId.of("Europe/Madrid"));
        limitador = new LimitadorRestablecimiento(new PropiedadesRestablecimiento(10, HORA), reloj);
    }

    @Test
    void registrarIntento_diezIntentosSePermitenYElOnceNo() { // CP-RST-20
        for (int i = 1; i <= 10; i++) {
            assertThat(limitador.registrarIntento(IP)).as("intento %d", i).isTrue();
        }

        assertThat(limitador.registrarIntento(IP)).isFalse();
    }

    @Test
    void registrarIntento_vuelveAPermitirCuandoElMasAntiguoSaleDeLaVentana() { // CP-RST-21
        // Given: diez intentos, uno por minuto (el primero a las 10:00)
        for (int i = 0; i < 10; i++) {
            if (i > 0) {
                reloj.avanzar(Duration.ofMinutes(1));
            }
            limitador.registrarIntento(IP);
        }
        assertThat(limitador.registrarIntento(IP)).isFalse();

        // When: llega el instante exacto en que el primero cumple la hora (11:00); estamos en las 10:09
        reloj.avanzar(Duration.ofMinutes(51));

        // Then: se libera un unico hueco
        assertThat(limitador.registrarIntento(IP)).isTrue();
        assertThat(limitador.registrarIntento(IP)).isFalse();
    }

    @Test
    void registrarIntento_insistirConElCupoAgotado_noAlargaElCastigo() { // CP-RST-22
        for (int i = 0; i < 10; i++) {
            limitador.registrarIntento(IP);
        }
        for (int i = 0; i < 20; i++) {
            reloj.avanzar(Duration.ofMinutes(2));
            limitador.registrarIntento(IP);
        }

        // Pasados 60 min desde los diez intentos (todos a las 10:00), se vuelve a permitir
        reloj.avanzar(Duration.ofMinutes(20));
        assertThat(limitador.registrarIntento(IP)).isTrue();
    }

    @Test
    void registrarIntento_ipsDistintas_tienenContadoresIndependientes() { // CP-RST-23
        for (int i = 0; i < 10; i++) {
            limitador.registrarIntento(IP);
        }

        assertThat(limitador.registrarIntento(IP)).isFalse();
        assertThat(limitador.registrarIntento("198.51.100.9")).isTrue();
    }

    @Test
    void registrarIntento_ipNulaOVacia_cuentaComoUnaUnicaIpDesconocidaSinFallar() { // CP-RST-24
        for (int i = 0; i < 5; i++) {
            assertThat(limitador.registrarIntento(null)).isTrue();
            assertThat(limitador.registrarIntento("  ")).isTrue();
        }

        assertThat(limitador.registrarIntento(null)).isFalse();
    }

    @Test
    void registrarIntento_cincuentaIntentosSimultaneos_pasanExactamenteDiez() throws Exception { // CP-RST-25
        int hilos = 50;
        ExecutorService ejecutor = Executors.newFixedThreadPool(hilos);
        CyclicBarrier salida = new CyclicBarrier(hilos);
        try {
            List<Future<Boolean>> futuros = new ArrayList<>();
            for (int i = 0; i < hilos; i++) {
                futuros.add(ejecutor.submit(() -> {
                    salida.await(5, TimeUnit.SECONDS);
                    return limitador.registrarIntento(IP);
                }));
            }
            long permitidos = 0;
            for (Future<Boolean> futuro : futuros) {
                if (futuro.get(10, TimeUnit.SECONDS)) {
                    permitidos++;
                }
            }

            assertThat(permitidos).isEqualTo(10);
        } finally {
            ejecutor.shutdownNow();
        }
    }

    @Test
    void memoria_lasIpsQueYaSalieronDeLaVentanaSeOlvidan() { // CP-RST-26
        for (int i = 0; i < 100; i++) {
            limitador.registrarIntento("10.0.0." + i);
        }
        assertThat(limitador.clavesSeguidas()).isEqualTo(100);

        reloj.avanzar(HORA.plusMinutes(1));
        limitador.registrarIntento("198.51.100.9");

        assertThat(limitador.clavesSeguidas()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ propiedades

    @ParameterizedTest(name = "maximo por IP {0}")
    @ValueSource(ints = { 0, -1, -100 })
    void propiedades_maximoMenorQueUno_seRechaza(int maximo) { // CP-RST-27
        assertThrows(IllegalArgumentException.class, () -> new PropiedadesRestablecimiento(maximo, HORA));
    }

    @Test
    void propiedades_ventanaNulaNoPositiva_seRechaza() { // CP-RST-28
        assertThrows(IllegalArgumentException.class, () -> new PropiedadesRestablecimiento(10, null));
        assertThrows(IllegalArgumentException.class, () -> new PropiedadesRestablecimiento(10, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new PropiedadesRestablecimiento(10, Duration.ofMinutes(-5)));
    }
}
