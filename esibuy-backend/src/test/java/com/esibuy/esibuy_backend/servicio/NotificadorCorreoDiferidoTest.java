package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import com.esibuy.esibuy_backend.configuracion.FiltroCorrelacionId;
import com.esibuy.esibuy_backend.util.CapturadorLogs;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * NotificadorCorreo.notificarDiferido: el mensaje se prepara tambien en segundo plano (HdU recuperacion de contrasena,
 * fase 2). Casos: CP-REC-20 a CP-REC-24. Complementa NotificadorCorreoTest (fase 0), que no se modifica.
 *
 * Por que existe: para recuperar la contrasena de una cuenta activa hay que emitir un token en la base de datos antes de
 * poder redactar el correo. Si eso se hiciera en la peticion, responder tardaria mas cuando el correo esta registrado
 * que cuando no, y el tiempo delataria las cuentas existentes. Con la preparacion diferida, esa diferencia ocurre en
 * segundo plano.
 *
 * Terminada: ya no lleva la etiqueta pendiente-recuperacion y entra en la regresion.
 */
class NotificadorCorreoDiferidoTest {

    private static final String DESTINATARIO = "ana@ejemplo.es";
    private static final MensajeCorreo MENSAJE = new MensajeCorreo(DESTINATARIO, "Recuperación", "Cuerpo del correo");
    private static final String ID_CORRELACION = "corr-diferido-1";

    private final AuditoriaSeguridad auditoria = new AuditoriaSeguridad();
    private final List<MensajeCorreo> enviados = new ArrayList<>();
    private final List<ExecutorService> ejecutores = new ArrayList<>();

    @BeforeEach
    void ponerIdDeCorrelacion() {
        MDC.put(FiltroCorrelacionId.CLAVE_MDC, ID_CORRELACION);
    }

    @AfterEach
    void limpiar() throws InterruptedException {
        MDC.remove(FiltroCorrelacionId.CLAVE_MDC);
        for (ExecutorService ejecutor : ejecutores) {
            ejecutor.shutdownNow();
            ejecutor.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    private ExecutorService ejecutorDeUnHilo() {
        ExecutorService ejecutor = Executors.newSingleThreadExecutor();
        ejecutores.add(ejecutor);
        return ejecutor;
    }

    private static List<ILoggingEvent> eventosDeFallo(CapturadorLogs logs) {
        return logs.eventos().stream()
                .filter(evento -> evento.getFormattedMessage().contains("CORREO_ENVIO_FALLIDO")).toList();
    }

    @Test
    void notificarDiferido_laPreparacionNoSeEjecutaEnElHiloDelLlamante() { // CP-REC-20
        // Given: una cola manual que guarda las tareas sin ejecutarlas
        List<Runnable> cola = new ArrayList<>();
        AtomicInteger preparaciones = new AtomicInteger();
        NotificadorCorreo notificador = new NotificadorCorreo(enviados::add, cola::add, auditoria);

        // When
        notificador.notificarDiferido(DESTINATARIO, () -> {
            preparaciones.incrementAndGet();
            return MENSAJE;
        });

        // Then: al volver, ni se ha preparado ni enviado nada...
        assertThat(preparaciones.get()).isZero();
        assertThat(enviados).isEmpty();
        assertThat(cola).hasSize(1);

        // ...y al ejecutarse la tarea, se prepara una vez y se envia
        cola.forEach(Runnable::run);
        assertThat(preparaciones.get()).isEqualTo(1);
        assertThat(enviados).containsExactly(MENSAJE);
    }

    @Test
    void notificarDiferido_laPreparacionDevuelveElMensaje_seEnviaTalCual() { // CP-REC-21
        NotificadorCorreo notificador = new NotificadorCorreo(enviados::add, Runnable::run, auditoria);

        notificador.notificarDiferido(DESTINATARIO, () -> MENSAJE);

        assertThat(enviados).containsExactly(MENSAJE);
    }

    @Test
    void notificarDiferido_fallaLaPreparacion_noSePropagaNoSeEnviaYSeAudita() { // CP-REC-22
        // Given: por ejemplo, la base de datos falla al emitir el token
        NotificadorCorreo notificador = new NotificadorCorreo(enviados::add, Runnable::run, auditoria);

        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            // When / Then
            assertDoesNotThrow(() -> notificador.notificarDiferido(DESTINATARIO, () -> {
                throw new IllegalStateException("mongo caido: " + DESTINATARIO);
            }));
            assertThat(enviados).isEmpty();
            assertThat(eventosDeFallo(logs)).singleElement().satisfies(evento -> {
                assertThat(evento.getLevel()).isEqualTo(Level.WARN);
                assertThat(evento.getFormattedMessage())
                        .contains("causa=IllegalStateException", "correlationId=" + ID_CORRELACION,
                                "email=" + EnmascaradorDatosLog.correo(DESTINATARIO))
                        .doesNotContain(DESTINATARIO, "mongo caido");
            });
        }
    }

    @Test
    void notificarDiferido_elEjecutorEstaSaturado_noSePropagaYSeAudita() { // CP-REC-23
        NotificadorCorreo notificador = new NotificadorCorreo(enviados::add, tarea -> {
            throw new RejectedExecutionException("cola llena");
        }, auditoria);

        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            assertDoesNotThrow(() -> notificador.notificarDiferido(DESTINATARIO, () -> MENSAJE));

            assertThat(enviados).isEmpty();
            assertThat(eventosDeFallo(logs)).singleElement().satisfies(evento ->
                    assertThat(evento.getFormattedMessage()).contains("causa=RejectedExecutionException"));
        }
    }

    @Test
    void notificarDiferido_enOtroHilo_conservaElCorrelationIdEnLaPreparacionYNoLoDeja() throws Exception { // CP-REC-24
        // Given
        ExecutorService ejecutor = ejecutorDeUnHilo();
        NotificadorCorreo notificador = new NotificadorCorreo(enviados::add, ejecutor, auditoria);
        List<String> idVistoEnLaPreparacion = new ArrayList<>();

        // When: la preparacion lee el MDC desde el hilo de segundo plano
        notificador.notificarDiferido(DESTINATARIO, () -> {
            idVistoEnLaPreparacion.add(MDC.get(FiltroCorrelacionId.CLAVE_MDC));
            return MENSAJE;
        });
        ejecutor.submit(() -> { }).get(5, TimeUnit.SECONDS);

        // Then: ve el id de la peticion original y despues el hilo queda limpio
        assertThat(idVistoEnLaPreparacion).containsExactly(ID_CORRELACION);
        assertThat(ejecutor.submit(() -> MDC.get(FiltroCorrelacionId.CLAVE_MDC)).get(5, TimeUnit.SECONDS)).isNull();
    }
}
