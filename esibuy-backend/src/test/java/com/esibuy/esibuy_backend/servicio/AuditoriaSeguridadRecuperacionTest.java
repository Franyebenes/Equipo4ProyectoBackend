package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import com.esibuy.esibuy_backend.configuracion.FiltroCorrelacionId;
import com.esibuy.esibuy_backend.modelo.EstadoUsuario;
import com.esibuy.esibuy_backend.util.CapturadorLogs;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Eventos de auditoria de la solicitud de recuperacion (HdU recuperacion de contrasena, fase 2).
 * Casos: CP-REC-30 a CP-REC-34. Complementa AuditoriaSeguridadTest (login), que no se modifica.
 *
 * Mismo formato que los eventos del login: una linea por evento con una palabra clave y datos clave=valor (ip,
 * userAgent, correlationId y email siempre enmascarado), con el user agent saneado. Eventos:
 *  - RECUPERACION_SOLICITADA (INFO): cuenta activa, con userId.
 *  - RECUPERACION_CUENTA_NO_ACTIVA (INFO): cuenta pendiente de activacion.
 *  - RECUPERACION_CUENTA_NO_APTA (WARN): cuenta bloqueada, con su estado.
 *  - RECUPERACION_EMAIL_DESCONOCIDO (INFO): correo no registrado.
 * Los motivos solo quedan en el log: hacia el cliente la respuesta es siempre la misma. Ningun evento incluye token.
 *
 * Terminada: ya no lleva la etiqueta pendiente-recuperacion y entra en la regresion.
 */
class AuditoriaSeguridadRecuperacionTest {

    private static final String ID_CORRELACION = "corr-rec-123";
    private static final String EMAIL = "ana@ejemplo.es";
    private static final String ID_USUARIO = "665f1c2e9b1e8a3d4c5b6a79";
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

    private static List<ILoggingEvent> eventosDeRecuperacion(CapturadorLogs logs) {
        return logs.eventos().stream()
                .filter(evento -> evento.getFormattedMessage().contains("RECUPERACION_")).toList();
    }

    private static String correoEnmascarado() {
        return EnmascaradorDatosLog.correo(EMAIL);
    }

    @Test
    void registrarRecuperacionSolicitada_emiteEventoInformativoConElIdDelUsuario() { // CP-REC-30
        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            auditoria.registrarRecuperacionSolicitada(CONTEXTO, EMAIL, ID_USUARIO);

            assertThat(eventosDeRecuperacion(logs)).singleElement().satisfies(evento -> {
                assertThat(evento.getLevel()).isEqualTo(Level.INFO);
                assertThat(evento.getFormattedMessage())
                        .contains("RECUPERACION_SOLICITADA", "ip=203.0.113.7", "userAgent=Mozilla/5.0 (JUnit)",
                                "correlationId=" + ID_CORRELACION, "email=" + correoEnmascarado(),
                                "userId=" + ID_USUARIO)
                        .doesNotContain(EMAIL);
            });
        }
    }

    @Test
    void registrarRecuperacionCuentaNoActiva_emiteEventoInformativoSinIdDeUsuario() { // CP-REC-31
        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            auditoria.registrarRecuperacionCuentaNoActiva(CONTEXTO, EMAIL);

            assertThat(eventosDeRecuperacion(logs)).singleElement().satisfies(evento -> {
                assertThat(evento.getLevel()).isEqualTo(Level.INFO);
                assertThat(evento.getFormattedMessage())
                        .contains("RECUPERACION_CUENTA_NO_ACTIVA", "ip=203.0.113.7", "userAgent=Mozilla/5.0 (JUnit)",
                                "correlationId=" + ID_CORRELACION, "email=" + correoEnmascarado())
                        .doesNotContain(EMAIL, "userId=");
            });
        }
    }

    @Test
    void registrarRecuperacionCuentaNoApta_emiteUnAvisoConElEstadoDeLaCuenta() { // CP-REC-32
        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            auditoria.registrarRecuperacionCuentaNoApta(CONTEXTO, EMAIL, EstadoUsuario.BLOQUEADO);

            assertThat(eventosDeRecuperacion(logs)).singleElement().satisfies(evento -> {
                assertThat(evento.getLevel()).isEqualTo(Level.WARN);
                assertThat(evento.getFormattedMessage())
                        .contains("RECUPERACION_CUENTA_NO_APTA", "ip=203.0.113.7", "correlationId=" + ID_CORRELACION,
                                "email=" + correoEnmascarado(), "estado=BLOQUEADO")
                        .doesNotContain(EMAIL, "userId=");
            });
        }
    }

    @Test
    void registrarRecuperacionCuentaNoApta_estadoDesconocido_noFallaYLoIndica() { // CP-REC-32
        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            auditoria.registrarRecuperacionCuentaNoApta(CONTEXTO, EMAIL, null);

            assertThat(eventosDeRecuperacion(logs)).singleElement().satisfies(evento ->
                    assertThat(evento.getFormattedMessage()).contains("RECUPERACION_CUENTA_NO_APTA", "estado="));
        }
    }

    @Test
    void registrarRecuperacionEmailDesconocido_emiteEventoInformativoSinRevelarNada() { // CP-REC-33
        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            auditoria.registrarRecuperacionEmailDesconocido(CONTEXTO, EMAIL);

            assertThat(eventosDeRecuperacion(logs)).singleElement().satisfies(evento -> {
                assertThat(evento.getLevel()).isEqualTo(Level.INFO);
                assertThat(evento.getFormattedMessage())
                        .contains("RECUPERACION_EMAIL_DESCONOCIDO", "ip=203.0.113.7",
                                "correlationId=" + ID_CORRELACION, "email=" + correoEnmascarado())
                        .doesNotContain(EMAIL, "userId=");
            });
        }
    }

    @Test
    void registrarRecuperacion_userAgentConSaltosDeLinea_noPermiteFalsificarOtraLineaDeLog() { // CP-REC-34
        ContextoPeticion malicioso = new ContextoPeticion("203.0.113.7",
                "Mozilla\r\n2026-10-16 INFO RECUPERACION_SOLICITADA email=admin");

        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            auditoria.registrarRecuperacionSolicitada(malicioso, EMAIL, ID_USUARIO);
            auditoria.registrarRecuperacionCuentaNoActiva(malicioso, EMAIL);
            auditoria.registrarRecuperacionEmailDesconocido(malicioso, EMAIL);

            assertThat(eventosDeRecuperacion(logs)).hasSize(3)
                    .allSatisfy(evento -> assertThat(evento.getFormattedMessage()).doesNotContain("\r", "\n"));
        }
    }
}
