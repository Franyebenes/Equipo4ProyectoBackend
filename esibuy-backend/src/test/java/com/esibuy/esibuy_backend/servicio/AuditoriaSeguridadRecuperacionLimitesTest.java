package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.MDC;

import com.esibuy.esibuy_backend.configuracion.FiltroCorrelacionId;
import com.esibuy.esibuy_backend.util.CapturadorLogs;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Evento de auditoria de las solicitudes limitadas (HdU recuperacion de contrasena, fase 3).
 * Casos: CP-LIM-30 a CP-LIM-32. Complementa AuditoriaSeguridadRecuperacionTest (fase 2).
 *
 * RECUPERACION_LIMITADA (WARN): una linea con ip, userAgent, correlationId, el correo siempre enmascarado y el motivo
 * (IP_AGOTADA, COOLDOWN o LIMITE_DIARIO). El motivo solo queda en el log: hacia el cliente la respuesta es siempre la
 * misma.
 *
 * Terminada: ya no lleva la etiqueta pendiente-recuperacion y entra en la regresion.
 */
class AuditoriaSeguridadRecuperacionLimitesTest {

    private static final String ID_CORRELACION = "corr-lim-123";
    private static final String EMAIL = "ana@ejemplo.es";
    private static final ContextoPeticion CONTEXTO = new ContextoPeticion("203.0.113.7", "Mozilla/5.0 (JUnit)");

    private final AuditoriaSeguridad auditoria = new AuditoriaSeguridad();

    @BeforeEach
    void ponerIdDeCorrelacion() {
        MDC.put(FiltroCorrelacionId.CLAVE_MDC, ID_CORRELACION);
    }

    @AfterEach
    void quitarIdDeCorrelacion() {
        MDC.remove(FiltroCorrelacionId.CLAVE_MDC);
    }

    private static List<ILoggingEvent> eventosLimitados(CapturadorLogs logs) {
        return logs.eventos().stream()
                .filter(evento -> evento.getFormattedMessage().contains("RECUPERACION_LIMITADA")).toList();
    }

    @ParameterizedTest(name = "motivo {0}")
    @EnumSource(MotivoLimiteRecuperacion.class)
    void registrarRecuperacionLimitada_emiteUnAvisoConElMotivoYLosDatosEnmascarados(
            MotivoLimiteRecuperacion motivo) { // CP-LIM-30
        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            auditoria.registrarRecuperacionLimitada(CONTEXTO, EMAIL, motivo);

            assertThat(eventosLimitados(logs)).singleElement().satisfies(evento -> {
                assertThat(evento.getLevel()).isEqualTo(Level.WARN);
                assertThat(evento.getFormattedMessage())
                        .contains("RECUPERACION_LIMITADA", "ip=203.0.113.7", "userAgent=Mozilla/5.0 (JUnit)",
                                "correlationId=" + ID_CORRELACION, "email=" + EnmascaradorDatosLog.correo(EMAIL),
                                "motivo=" + motivo.name())
                        .doesNotContain(EMAIL, "userId=");
            });
        }
    }

    @Test
    void registrarRecuperacionLimitada_userAgentConSaltosDeLinea_noPermiteFalsificarOtraLineaDeLog() { // CP-LIM-31
        ContextoPeticion malicioso = new ContextoPeticion("203.0.113.7",
                "Mozilla\r\n2026-10-17 INFO RECUPERACION_LIMITADA email=admin");

        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            auditoria.registrarRecuperacionLimitada(malicioso, EMAIL, MotivoLimiteRecuperacion.COOLDOWN);

            assertThat(eventosLimitados(logs)).singleElement().satisfies(evento ->
                    assertThat(evento.getFormattedMessage()).doesNotContain("\r", "\n"));
        }
    }

    @Test
    void registrarRecuperacionLimitada_motivoNulo_noFalla() { // CP-LIM-32
        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            auditoria.registrarRecuperacionLimitada(CONTEXTO, EMAIL, null);

            assertThat(eventosLimitados(logs)).singleElement().satisfies(evento ->
                    assertThat(evento.getFormattedMessage()).contains("motivo="));
        }
    }
}
