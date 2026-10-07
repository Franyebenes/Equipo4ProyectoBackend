package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.esibuy.esibuy_backend.excepcion.LoginBloqueadoTemporalmenteException;
import com.esibuy.esibuy_backend.util.RelojMutable;

/**
 * Bloqueo progresivo, límite de intentos y modo adaptativo por IP.
 *
 * Orden TDD (plan de pruebas, sección 8): paso 2.
 * Casos: CP-BLQ-01, CP-BLQ-02, CP-BLQ-04, CP-BLQ-05, CP-BLQ-06, CP-BLQ-11, CP-BLQ-13, CP-BLQ-15, CP-BLQ-17.
 * Colaboradores: Reloj mutable (que avanza a voluntad), Pausador (el retraso de 2 s) y AlertasSeguridad
 * mockeados. Ninguna prueba espera tiempo real.
 *
 * Contrato que fijan estas pruebas:
 *  - comprobarIntento(correo, ip): puerta de entrada de cada intento. Lanza LoginBloqueadoTemporalmenteException si
 *    la cuenta está bloqueada o si se supera el límite de intentos de esa IP y usuario. Un intento que se rechaza no
 *    cuenta para el límite. Es lo único que cuenta intentos para el límite por IP y para el modo adaptativo.
 *  - registrarFallo(correo, ip) / registrarExito(correo): llevan el contador de fallos de la cuenta y el nivel de
 *    escalado. Con 5 fallos acumulados se bloquea la cuenta; un fallo nuevo cuando ya no hay bloqueo vigente y el
 *    contador sigue en 5 o más activa el bloqueo del nivel siguiente. Los fallos que llegan con el bloqueo vigente
 *    suman al contador pero no escalan. Un éxito reinicia contador y nivel.
 *  - fallosConsecutivos(correo): contador actual de la cuenta (correo normalizado).
 *  - Ventanas: un fallo se suma al anterior si han pasado 10 minutos o menos; un intento cuenta para el límite
 *    mientras no hayan pasado 5 minutos completos.
 *
 * Desarrollada con el ciclo Red-Green-Refactor: primero el test (Arrange, Act, Assert), después el código
 * mínimo que lo hace pasar. Terminada: ya no lleva la etiqueta pendiente-login y entra en la regresión.
 */
@ExtendWith(MockitoExtension.class)
class LimitadorIntentosLoginTest {

    private static final String CORREO = "ana.garcia@ejemplo.es";
    private static final String IP = "203.0.113.7";
    private static final String OTRA_IP = "198.51.100.20";
    private static final Duration CINCO_MINUTOS = Duration.ofMinutes(5);
    private static final Duration RETRASO_ADAPTATIVO = Duration.ofSeconds(2);
    private static final int FALLOS_PARA_BLOQUEAR = 5;

    @Mock
    private Pausador pausador;
    @Mock
    private AlertasSeguridad alertas;

    private RelojMutable reloj;
    private LimitadorIntentosLogin limitador;

    @BeforeEach
    void prepararLimitador() {
        reloj = new RelojMutable(Instant.parse("2026-10-06T10:00:00Z"), ZoneId.of("Europe/Madrid"));
        limitador = new LimitadorIntentosLogin(reloj, pausador, alertas);
    }

    // ------------------------------------------------------------------ apoyo

    private void registrarFallos(String correo, String ip, int cantidad) {
        for (int i = 0; i < cantidad; i++) {
            limitador.registrarFallo(correo, ip);
        }
    }

    private long segundosRestantesDelBloqueo(String correo, String ip) {
        LoginBloqueadoTemporalmenteException bloqueo = assertThrows(LoginBloqueadoTemporalmenteException.class,
                () -> limitador.comprobarIntento(correo, ip));
        return bloqueo.getSegundosRestantes();
    }

    private void comprobarQueNoEstaBloqueado(String correo, String ip) {
        assertThatCode(() -> limitador.comprobarIntento(correo, ip)).doesNotThrowAnyException();
    }

    private static String usuario(int numero) {
        return "usuario" + numero + "@ejemplo.es";
    }

    // ------------------------------------------------------------------ CP-BLQ-01

    @Test
    void registrarFallo_cuatroFallosYUnQuinto_bloqueaSoloAlQuinto() { // CP-BLQ-01
        // Given: cuatro fallos del mismo correo
        registrarFallos(CORREO, IP, FALLOS_PARA_BLOQUEAR - 1);

        // When / Then: todavía puede intentarlo
        comprobarQueNoEstaBloqueado(CORREO, IP);

        // When: el quinto fallo
        limitador.registrarFallo(CORREO, IP);

        // Then: bloqueo de 30 segundos
        assertThat(limitador.fallosConsecutivos(CORREO)).isEqualTo(FALLOS_PARA_BLOQUEAR);
        assertThat(segundosRestantesDelBloqueo(CORREO, IP)).isEqualTo(30);
    }

    // ------------------------------------------------------------------ CP-BLQ-02

    static Stream<Arguments> nivelesDeBloqueo() {
        return Stream.of(
                arguments(1, 30),
                arguments(2, 120),
                arguments(3, 240),
                arguments(4, 300),
                // A partir del cuarto nivel se mantiene en 5 minutos
                arguments(5, 300),
                arguments(6, 300));
    }

    @ParameterizedTest(name = "nivel {0} -> bloqueo de {1} s")
    @MethodSource("nivelesDeBloqueo")
    void registrarFallo_bloqueosSucesivos_escalaDe30SegundosA5MinutosYSeMantiene( // CP-BLQ-02
            int nivel, int segundosDelNivel) {
        // Given: la cuenta ha pasado por los niveles anteriores; al acabar cada bloqueo se vuelve a fallar
        registrarFallos(CORREO, IP, FALLOS_PARA_BLOQUEAR);
        int segundosDelBloqueoAnterior = 30;
        for (int actual = 2; actual <= nivel; actual++) {
            reloj.avanzar(Duration.ofSeconds(segundosDelBloqueoAnterior));
            limitador.registrarFallo(CORREO, IP);
            segundosDelBloqueoAnterior = (int) segundosRestantesDelBloqueo(CORREO, IP);
        }

        // Then: el bloqueo del nivel dura lo previsto
        assertThat(segundosRestantesDelBloqueo(CORREO, IP)).isEqualTo(segundosDelNivel);

        // Then: el tiempo restante se redondea hacia arriba y nunca baja de 1 (valor de Retry-After)
        reloj.avanzar(Duration.ofMillis(segundosDelNivel * 1000L - 1500));
        assertThat(segundosRestantesDelBloqueo(CORREO, IP)).isEqualTo(2);
        reloj.avanzar(Duration.ofMillis(500));
        assertThat(segundosRestantesDelBloqueo(CORREO, IP)).isEqualTo(1);
        reloj.avanzar(Duration.ofMillis(500));
        assertThat(segundosRestantesDelBloqueo(CORREO, IP)).isEqualTo(1);

        // Then: en el instante exacto del final queda libre
        reloj.avanzar(Duration.ofMillis(500));
        comprobarQueNoEstaBloqueado(CORREO, IP);
    }

    // ------------------------------------------------------------------ CP-BLQ-04

    @ParameterizedTest(name = "cuatro fallos y otro {0} s después -> {1} fallos acumulados")
    @CsvSource({
            // Hasta 10 minutos exactos todavía se acumulan: 5 fallos, bloqueo
            "599, 5",
            "600, 5",
            // Pasados más de 10 minutos el contador empieza de nuevo
            "601, 1"})
    void registrarFallo_fallosSeparadosPorMasDe10Minutos_noSeAcumulan( // CP-BLQ-04
            long segundosDeSeparacion, int fallosEsperados) {
        // Given: cuatro fallos seguidos
        registrarFallos(CORREO, IP, FALLOS_PARA_BLOQUEAR - 1);

        // When: el siguiente fallo llega tras la separación indicada
        reloj.avanzar(Duration.ofSeconds(segundosDeSeparacion));
        limitador.registrarFallo(CORREO, IP);

        // Then: solo alcanza el umbral si se acumula con los anteriores
        assertThat(limitador.fallosConsecutivos(CORREO)).isEqualTo(fallosEsperados);
        if (fallosEsperados >= FALLOS_PARA_BLOQUEAR) {
            assertThat(segundosRestantesDelBloqueo(CORREO, IP)).isEqualTo(30);
        } else {
            comprobarQueNoEstaBloqueado(CORREO, IP);
        }
    }

    // ------------------------------------------------------------------ CP-BLQ-05

    @Test
    void registrarExito_trasVariosFallos_reiniciaContadorYNivelDeEscalado() { // CP-BLQ-05
        // Given: cuatro fallos
        registrarFallos(CORREO, IP, FALLOS_PARA_BLOQUEAR - 1);

        // When: un inicio de sesión correcto
        limitador.registrarExito(CORREO);

        // Then: contador a cero y cuatro fallos más no bloquean
        assertThat(limitador.fallosConsecutivos(CORREO)).isZero();
        registrarFallos(CORREO, IP, FALLOS_PARA_BLOQUEAR - 1);
        comprobarQueNoEstaBloqueado(CORREO, IP);
    }

    @Test
    void registrarExito_trasEscalarElBloqueo_elSiguienteBloqueoVuelveAlPrimerNivel() { // CP-BLQ-05
        // Given: la cuenta estuvo en el segundo nivel de bloqueo (120 s) y ya ha terminado
        registrarFallos(CORREO, IP, FALLOS_PARA_BLOQUEAR);
        reloj.avanzar(Duration.ofSeconds(30));
        limitador.registrarFallo(CORREO, IP);
        assertThat(segundosRestantesDelBloqueo(CORREO, IP)).isEqualTo(120);
        reloj.avanzar(Duration.ofSeconds(120));

        // When: entra con éxito y después falla de nuevo hasta el umbral
        limitador.registrarExito(CORREO);
        registrarFallos(CORREO, IP, FALLOS_PARA_BLOQUEAR);

        // Then: el bloqueo es otra vez el del primer nivel
        assertThat(segundosRestantesDelBloqueo(CORREO, IP)).isEqualTo(30);
    }

    // ------------------------------------------------------------------ CP-BLQ-06

    @Test
    void registrarFallo_variantesDelCorreoEIpsDistintas_sumanSobreLaMismaCuenta() { // CP-BLQ-06
        // Given: cinco fallos con variantes del mismo correo y desde cinco IPs distintas
        limitador.registrarFallo("ANA.GARCIA@ejemplo.es", IP);
        limitador.registrarFallo("  ana.garcia@EJEMPLO.es ", OTRA_IP);
        limitador.registrarFallo(CORREO, "192.0.2.1");
        limitador.registrarFallo("Ana.Garcia@Ejemplo.Es", "192.0.2.2");
        limitador.registrarFallo("ana.garcia@ejemplo.es\t", "192.0.2.3");

        // Then: es una sola cuenta, bloqueada también para una IP que no ha fallado nunca
        assertThat(limitador.fallosConsecutivos(CORREO)).isEqualTo(FALLOS_PARA_BLOQUEAR);
        assertThat(segundosRestantesDelBloqueo(CORREO, "192.0.2.99")).isEqualTo(30);
        // Then: otra cuenta no se ve afectada
        comprobarQueNoEstaBloqueado("otra.cuenta@ejemplo.es", IP);
        assertThat(limitador.fallosConsecutivos("otra.cuenta@ejemplo.es")).isZero();
    }

    // ------------------------------------------------------------------ CP-BLQ-11

    @Test
    void comprobarIntento_sextoIntentoEn5Minutos_rechazaAEsaIpYNoAfectaAOtra() { // CP-BLQ-11
        // Given: cinco intentos en 5 minutos, entre fallos y éxitos (sin llegar al bloqueo de la cuenta)
        for (int intento = 1; intento <= 5; intento++) {
            comprobarQueNoEstaBloqueado(CORREO, IP);
            if (intento == 1 || intento == 3) {
                limitador.registrarFallo(CORREO, IP);
            } else {
                limitador.registrarExito(CORREO);
            }
        }

        // When / Then: el sexto se rechaza, hasta que pasen los 5 minutos
        assertThat(segundosRestantesDelBloqueo(CORREO, IP)).isEqualTo(300);
        reloj.avanzar(CINCO_MINUTOS.minusSeconds(1));
        assertThat(segundosRestantesDelBloqueo(CORREO, IP)).isEqualTo(1);

        // Then: el mismo correo desde otra IP no se ve afectado
        comprobarQueNoEstaBloqueado(CORREO, OTRA_IP);

        // Then: pasados los 5 minutos completos vuelve a poder intentarlo
        reloj.avanzar(Duration.ofSeconds(1));
        comprobarQueNoEstaBloqueado(CORREO, IP);
    }

    // ------------------------------------------------------------------ CP-BLQ-13

    @ParameterizedTest(name = "{0} usuarios distintos desde una IP -> modo adaptativo: {1}")
    @CsvSource({
            "19, false",
            "20, true"})
    void comprobarIntento_ipQueProbaUsuariosDistintos_entraEnModoAdaptativoAlLlegarA20( // CP-BLQ-13
            int usuariosDistintos, boolean entraEnModoAdaptativo) {
        // When: una IP prueba un usuario distinto en cada intento, dentro de 5 minutos
        for (int numero = 1; numero <= usuariosDistintos; numero++) {
            comprobarQueNoEstaBloqueado(usuario(numero), IP);
        }

        // Then: solo con 20 se avisa y se aplica el retraso fijo de 2 s (también a ese intento número 20)
        if (entraEnModoAdaptativo) {
            verify(alertas).ipEnModoAdaptativo(IP, usuariosDistintos);
            verify(pausador).pausar(RETRASO_ADAPTATIVO);
        } else {
            verifyNoInteractions(alertas, pausador);
        }

        // When: segundo intento del primer usuario (dentro de los dos permitidos en modo adaptativo)
        comprobarQueNoEstaBloqueado(usuario(1), IP);

        // Then: en modo adaptativo se vuelve a esperar 2 s
        if (entraEnModoAdaptativo) {
            verify(pausador, times(2)).pausar(RETRASO_ADAPTATIVO);
        } else {
            verifyNoInteractions(pausador);
        }

        // When / Then: el tercero pasa con el umbral normal (5) y se rechaza en modo adaptativo (2)
        if (entraEnModoAdaptativo) {
            assertThat(segundosRestantesDelBloqueo(usuario(1), IP)).isEqualTo(300);
        } else {
            comprobarQueNoEstaBloqueado(usuario(1), IP);
        }
    }

    // ------------------------------------------------------------------ CP-BLQ-15

    @Test
    void comprobarIntento_pasadaLaVentanaDelModoAdaptativo_laIpVuelveAlUmbralNormal() { // CP-BLQ-15
        // Given: la IP entra en modo adaptativo con 20 usuarios distintos
        for (int numero = 1; numero <= 20; numero++) {
            comprobarQueNoEstaBloqueado(usuario(numero), IP);
        }
        verify(alertas).ipEnModoAdaptativo(IP, 20);

        // When / Then: un segundo antes de acabar la ventana sigue en modo adaptativo (2 intentos y retraso)
        reloj.avanzar(CINCO_MINUTOS.minusSeconds(1));
        comprobarQueNoEstaBloqueado(usuario(1), IP);
        assertThat(segundosRestantesDelBloqueo(usuario(1), IP)).isEqualTo(1);
        verify(pausador, times(2)).pausar(RETRASO_ADAPTATIVO);

        // When: pasan los 5 minutos completos
        reloj.avanzar(Duration.ofSeconds(1));
        clearInvocations(pausador, alertas);

        // Then: umbral normal otra vez (5 intentos por IP y usuario), sin retraso ni nuevos avisos
        for (int intento = 1; intento <= 5; intento++) {
            comprobarQueNoEstaBloqueado(usuario(2), IP);
        }
        assertThat(segundosRestantesDelBloqueo(usuario(2), IP)).isEqualTo(300);
        verifyNoInteractions(pausador, alertas);
    }

    // ------------------------------------------------------------------ CP-BLQ-17

    @Test
    void registrarFallo_cincuentaFallosSimultaneos_contadorExactoSinPerdidas() throws Exception { // CP-BLQ-17
        // Given: 50 hilos listos para fallar a la vez con el mismo correo
        int hilos = 50;
        ExecutorService ejecutor = Executors.newFixedThreadPool(hilos);
        CountDownLatch salida = new CountDownLatch(1);
        try {
            List<Future<Void>> tareas = new ArrayList<>();
            for (int i = 0; i < hilos; i++) {
                Callable<Void> fallo = () -> {
                    salida.await();
                    limitador.registrarFallo(CORREO, IP);
                    return null;
                };
                tareas.add(ejecutor.submit(fallo));
            }

            // When: salen todos a la vez
            salida.countDown();
            for (Future<Void> tarea : tareas) {
                tarea.get(10, TimeUnit.SECONDS);
            }
        } finally {
            ejecutor.shutdownNow();
        }

        // Then: ninguna actualización perdida
        assertThat(limitador.fallosConsecutivos(CORREO)).isEqualTo(hilos);
        // Then: el bloqueo es coherente: uno solo, de primer nivel (los fallos con la cuenta ya bloqueada no escalan)
        assertThat(segundosRestantesDelBloqueo(CORREO, IP)).isEqualTo(30);
    }
}
