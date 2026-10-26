package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import com.esibuy.esibuy_backend.configuracion.FiltroCorrelacionId;
import com.esibuy.esibuy_backend.util.CapturadorLogs;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Evento de auditoria del primer paso del 2FA en el registro (HdU 2FA en el registro, fase A). Casos: CP-2FA-411 a
 * CP-2FA-413. REGISTRO_DOS_FACTORES_INICIADO (INFO): alguien ha pedido el QR para registrarse; con la IP, el user agent
 * (saneado), el correlationId y el correo siempre enmascarado. Todavia no hay cuenta, asi que no lleva userId. Nunca
 * incluye el secreto.
 *
 * Terminada: ya no lleva la etiqueta pendiente-2fa y entra en la regresion.
 */
class AuditoriaSeguridadRegistroDosFactoresTest {

    private static final String ID_CORRELACION = "corr-reg2fa-123";
    private static final String EMAIL = "lucia@ejemplo.es";
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

    private static List<ILoggingEvent> eventosDelRegistro(CapturadorLogs logs) {
        return logs.eventos().stream()
                .filter(evento -> evento.getFormattedMessage().contains("REGISTRO_DOS_FACTORES_")).toList();
    }

    @Test
    void registrarRegistroDosFactoresIniciado_emiteEventoInformativoConElCorreoEnmascarado() { // CP-2FA-411
        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            auditoria.registrarRegistroDosFactoresIniciado(CONTEXTO, EMAIL);

            assertThat(eventosDelRegistro(logs)).singleElement().satisfies(evento -> {
                assertThat(evento.getLevel()).isEqualTo(Level.INFO);
                assertThat(evento.getFormattedMessage()).contains("REGISTRO_DOS_FACTORES_INICIADO", "ip=203.0.113.7",
                        "userAgent=Mozilla/5.0 (JUnit)", "correlationId=" + ID_CORRELACION,
                        "email=" + EnmascaradorDatosLog.correo(EMAIL)).doesNotContain(EMAIL, "userId=");
            });
        }
    }

    @Test
    void registrarRegistroDosFactoresIniciado_elUserAgentSeSanea() { // CP-2FA-412
        ContextoPeticion malicioso = new ContextoPeticion("203.0.113.7", "Mozilla\r\nREGISTRO_DOS_FACTORES_INICIADO falso");
        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            auditoria.registrarRegistroDosFactoresIniciado(malicioso, EMAIL);

            // Un salto de linea en el user agent no puede falsificar otra linea del log
            assertThat(eventosDelRegistro(logs)).hasSize(1).allSatisfy(evento ->
                    assertThat(evento.getFormattedMessage()).doesNotContain("\n", "\r"));
        }
    }

    @Test
    void registrarRegistroDosFactoresIniciado_conCorreoNuloNoLanzaExcepcion() { // CP-2FA-413
        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            auditoria.registrarRegistroDosFactoresIniciado(CONTEXTO, null);

            assertThat(eventosDelRegistro(logs)).hasSize(1);
        }
    }
}
