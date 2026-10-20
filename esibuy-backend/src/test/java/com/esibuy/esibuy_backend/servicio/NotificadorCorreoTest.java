package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import com.esibuy.esibuy_backend.configuracion.FiltroCorrelacionId;
import com.esibuy.esibuy_backend.excepcion.FalloEnvioCorreoException;
import com.esibuy.esibuy_backend.util.CapturadorLogs;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Envio en segundo plano sin propagar fallos y con auditoria (HdU recuperacion de contrasena, fase 0).
 * Casos: CP-COR-70 a CP-COR-77.
 *
 * Por que existe: si el envio fuera sincrono, la respuesta de "recuperar contrasena" tardaria mas cuando el correo
 * existe (hay que hablar con el SMTP) que cuando no existe, y un atacante distinguiria cuentas registradas midiendo
 * el tiempo. Y si un fallo del SMTP llegara al cliente, tambien lo revelaria.
 *
 * Evento que fijan: una linea CORREO_ENVIO_FALLIDO a nivel WARN con el correo enmascarado, el correlationId de la
 * peticion que lo origino y solo el NOMBRE de la clase de la causa (nunca su mensaje, que puede contener direcciones,
 * servidores o credenciales).
 *
 * Terminada: ya no lleva la etiqueta pendiente-recuperacion y entra en la regresion.
 */
class NotificadorCorreoTest {

    private static final String DESTINATARIO = "ana@ejemplo.es";
    private static final String TOKEN_SECRETO = "TOKEN-SECRETO-9876543210";
    private static final MensajeCorreo MENSAJE = new MensajeCorreo(DESTINATARIO, "Recuperación",
            "Enlace: https://esibuy.es/restablecer-contrasena#token=" + TOKEN_SECRETO);
    private static final String ID_CORRELACION = "corr-correo-1";

    private final AuditoriaSeguridad auditoria = new AuditoriaSeguridad();
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

    private static void esperarAQueTerminen(ExecutorService ejecutor) throws InterruptedException {
        ejecutor.shutdown();
        assertThat(ejecutor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    private static EnviadorCorreo enviadorQueFalla(RuntimeException error) {
        return mensaje -> {
            throw error;
        };
    }

    private static List<ILoggingEvent> eventosDeFallo(CapturadorLogs logs) {
        return logs.eventos().stream()
                .filter(evento -> evento.getFormattedMessage().contains("CORREO_ENVIO_FALLIDO")).toList();
    }

    // ------------------------------------------------------------------ CP-COR-70 / 71

    @Test
    void notificar_entregaElMensajeAlEnviador() { // CP-COR-70
        // Given: un ejecutor que corre la tarea en el mismo hilo, para comprobar sin esperas
        List<MensajeCorreo> enviados = new ArrayList<>();
        NotificadorCorreo notificador = new NotificadorCorreo(enviados::add, Runnable::run, auditoria);

        // When
        notificador.notificar(MENSAJE);

        // Then
        assertThat(enviados).containsExactly(MENSAJE);
    }

    @Test
    void notificar_elEnvioLentoNoBloqueaAlLlamante() throws Exception { // CP-COR-71
        // Given: un SMTP lento (bloqueado hasta que el test lo libere)
        CountDownLatch haEmpezado = new CountDownLatch(1);
        CountDownLatch liberar = new CountDownLatch(1);
        List<MensajeCorreo> enviados = new ArrayList<>();
        EnviadorCorreo lento = mensaje -> {
            haEmpezado.countDown();
            try {
                liberar.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            enviados.add(mensaje);
        };
        ExecutorService ejecutor = ejecutorDeUnHilo();
        NotificadorCorreo notificador = new NotificadorCorreo(lento, ejecutor, auditoria);

        // When / Then: notificar vuelve enseguida aunque el envio siga en curso
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> notificador.notificar(MENSAJE));
        assertThat(haEmpezado.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(enviados).isEmpty();

        // y el envio termina despues, en segundo plano
        liberar.countDown();
        esperarAQueTerminen(ejecutor);
        assertThat(enviados).containsExactly(MENSAJE);
    }

    // ------------------------------------------------------------------ CP-COR-72 / 73 / 74

    @Test
    void notificar_elEnviadorFalla_noSePropagaLaExcepcionAlLlamante() { // CP-COR-72
        NotificadorCorreo notificador = new NotificadorCorreo(
                enviadorQueFalla(new FalloEnvioCorreoException("fallo", null)), Runnable::run, auditoria);

        assertDoesNotThrow(() -> notificador.notificar(MENSAJE));
    }

    @Test
    void notificar_elEnviadorFallaConUnErrorInesperado_tampocoSePropaga() { // CP-COR-73
        NotificadorCorreo notificador = new NotificadorCorreo(
                enviadorQueFalla(new IllegalStateException("error raro")), Runnable::run, auditoria);

        assertDoesNotThrow(() -> notificador.notificar(MENSAJE));
    }

    @Test
    void notificar_elEjecutorEstaSaturado_noSePropagaYNoSeEnvia() { // CP-COR-74
        // Given: un ejecutor lleno, que rechaza la tarea
        List<MensajeCorreo> enviados = new ArrayList<>();
        NotificadorCorreo notificador = new NotificadorCorreo(enviados::add, tarea -> {
            throw new RejectedExecutionException("cola llena");
        }, auditoria);

        // When / Then
        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            assertDoesNotThrow(() -> notificador.notificar(MENSAJE));
            assertThat(enviados).isEmpty();
            // y queda constancia del fallo
            assertThat(eventosDeFallo(logs)).singleElement().satisfies(evento ->
                    assertThat(evento.getFormattedMessage()).contains("causa=RejectedExecutionException"));
        }
    }

    // ------------------------------------------------------------------ CP-COR-75 / 76 / 77

    @Test
    void notificar_envioCorrecto_noRegistraNingunFallo() { // CP-COR-75
        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            new NotificadorCorreo(mensaje -> { }, Runnable::run, auditoria).notificar(MENSAJE);

            assertThat(eventosDeFallo(logs)).isEmpty();
        }
    }

    @Test
    void notificar_elEnvioFalla_registraUnAvisoConCorreoEnmascaradoYSoloElNombreDeLaCausa() { // CP-COR-76
        // Given: una causa cuyo mensaje trae datos sensibles
        String mensajeSensible = "535 clave incorrecta para usuario-smtp@smtp.interno.es " + TOKEN_SECRETO;
        NotificadorCorreo notificador = new NotificadorCorreo(
                enviadorQueFalla(new FalloEnvioCorreoException("No se pudo enviar el correo",
                        new IllegalStateException(mensajeSensible))), Runnable::run, auditoria);

        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            // When
            notificador.notificar(MENSAJE);

            // Then: un aviso WARN con la direccion enmascarada y solo el nombre de la clase del fallo
            assertThat(eventosDeFallo(logs)).singleElement().satisfies(evento -> {
                assertThat(evento.getLevel()).isEqualTo(Level.WARN);
                assertThat(evento.getFormattedMessage())
                        .contains("CORREO_ENVIO_FALLIDO", "email=" + EnmascaradorDatosLog.correo(DESTINATARIO),
                                "causa=FalloEnvioCorreoException", "correlationId=" + ID_CORRELACION)
                        .doesNotContain(DESTINATARIO, TOKEN_SECRETO, "usuario-smtp", "smtp.interno.es", "535");
            });
            // ni siquiera en la traza o los argumentos del evento
            assertThat(logs.textosCompletos()).noneMatch(texto -> texto.contains(TOKEN_SECRETO));
        }
    }

    // ------------------------------------------------------------------ CP-COR-77

    @Test
    void notificar_elFalloOcurreEnOtroHilo_conservaElCorrelationIdYNoLoDejaEnElHiloTrabajador() throws Exception {
        // CP-COR-77
        // Given: el envio se hace en un hilo distinto al de la peticion (que es el que tiene el MDC)
        ExecutorService ejecutor = ejecutorDeUnHilo();
        NotificadorCorreo notificador = new NotificadorCorreo(
                enviadorQueFalla(new FalloEnvioCorreoException("fallo", null)), ejecutor, auditoria);

        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            // When
            notificador.notificar(MENSAJE);
            ejecutor.submit(() -> { }).get(5, TimeUnit.SECONDS); // espera a que el hilo termine la tarea anterior

            // Then: el aviso lleva el id de la peticion original...
            assertThat(eventosDeFallo(logs)).singleElement().satisfies(evento ->
                    assertThat(evento.getFormattedMessage()).contains("correlationId=" + ID_CORRELACION));
        }
        // ...y el hilo trabajador no se queda con el id de una peticion ajena para las siguientes tareas
        assertThat(ejecutor.submit(() -> MDC.get(FiltroCorrelacionId.CLAVE_MDC)).get(5, TimeUnit.SECONDS)).isNull();
    }
}
