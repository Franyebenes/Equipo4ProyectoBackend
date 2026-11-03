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
 * Eventos de auditoria del registro final con 2FA (HdU 2FA en el registro, fase B). Casos: CP-2FA-476 a CP-2FA-483.
 *
 *  - REGISTRO_DOS_FACTORES_COMPLETADO (INFO): cuenta creada con el 2FA activo; con IP, user agent (saneado), correlationId,
 *    correo enmascarado y userId.
 *  - REGISTRO_DOS_FACTORES_FALLIDO (WARN): el 2FA rechazo el registro; con el motivo (CODIGO_INCORRECTO o ACTIVACION_NO_VIGENTE),
 *    el correo enmascarado y sin userId (la cuenta no existe).
 *  - REGISTRO_DOS_FACTORES_AGOTADO (WARN): el secreto pendiente se descarto por agotar los codigos fallidos; sin correo.
 * Ninguno incluye el codigo, el secreto ni los codigos de recuperacion (ni los reciben).
 */
class AuditoriaSeguridadRegistroDosFactoresFinalTest {

    private static final String ID_CORRELACION = "corr-reg2fa-final-1";
    private static final String EMAIL = "lucia@ejemplo.es";
    private static final String ID_USUARIO = "665f1c2e8a1b2c3d4e5f6a7b";
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

    private static List<ILoggingEvent> eventos(CapturadorLogs logs, String nombre) {
        return logs.eventos().stream().filter(evento -> evento.getLoggerName().startsWith("com.esibuy"))
                .filter(evento -> evento.getFormattedMessage().contains(nombre)).toList();
    }

    @Test
    void registrarCompletado_emiteEventoInformativoConElUsuarioYElCorreoEnmascarado() { // CP-2FA-476
        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            auditoria.registrarRegistroDosFactoresCompletado(CONTEXTO, EMAIL, ID_USUARIO);

            assertThat(eventos(logs, "REGISTRO_DOS_FACTORES_COMPLETADO")).singleElement().satisfies(evento -> {
                assertThat(evento.getLevel()).isEqualTo(Level.INFO);
                assertThat(evento.getFormattedMessage()).contains("ip=203.0.113.7", "userAgent=Mozilla/5.0 (JUnit)",
                        "correlationId=" + ID_CORRELACION, "email=" + EnmascaradorDatosLog.correo(EMAIL),
                        "userId=" + EnmascaradorDatosLog.texto(ID_USUARIO)).doesNotContain(EMAIL);
            });
        }
    }

    @Test
    void registrarCompletado_elUserAgentSeSaneaYUnCorreoNuloNoLanzaExcepcion() { // CP-2FA-477
        ContextoPeticion malicioso = new ContextoPeticion("203.0.113.7", "Mozilla\r\nREGISTRO_DOS_FACTORES_COMPLETADO falso");
        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            auditoria.registrarRegistroDosFactoresCompletado(malicioso, null, ID_USUARIO);

            assertThat(eventos(logs, "REGISTRO_DOS_FACTORES_COMPLETADO")).hasSize(1).allSatisfy(evento ->
                    assertThat(evento.getFormattedMessage()).doesNotContain("\n", "\r"));
        }
    }

    @Test
    void registrarFallido_emiteAvisoConElMotivoYSinUsuario() { // CP-2FA-478
        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            auditoria.registrarRegistroDosFactoresFallido(CONTEXTO, EMAIL, MotivoFalloDosFactores.CODIGO_INCORRECTO);

            assertThat(eventos(logs, "REGISTRO_DOS_FACTORES_FALLIDO")).singleElement().satisfies(evento -> {
                assertThat(evento.getLevel()).isEqualTo(Level.WARN);
                assertThat(evento.getFormattedMessage()).contains("motivo=CODIGO_INCORRECTO", "ip=203.0.113.7",
                        "correlationId=" + ID_CORRELACION, "email=" + EnmascaradorDatosLog.correo(EMAIL))
                        .doesNotContain(EMAIL, "userId=");
            });
        }
    }

    @Test
    void registrarFallido_registraElMotivoActivacionNoVigente() { // CP-2FA-479
        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            auditoria.registrarRegistroDosFactoresFallido(CONTEXTO, EMAIL, MotivoFalloDosFactores.ACTIVACION_NO_VIGENTE);

            assertThat(eventos(logs, "REGISTRO_DOS_FACTORES_FALLIDO")).singleElement().satisfies(evento ->
                    assertThat(evento.getFormattedMessage()).contains("motivo=ACTIVACION_NO_VIGENTE"));
        }
    }

    @Test
    void registrarFallido_conMotivoNuloOCorreoNuloNoLanzaExcepcion() { // CP-2FA-480
        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            auditoria.registrarRegistroDosFactoresFallido(CONTEXTO, null, null);

            assertThat(eventos(logs, "REGISTRO_DOS_FACTORES_FALLIDO")).hasSize(1);
        }
    }

    @Test
    void registrarAgotado_emiteAvisoSinCorreoNiUsuario() { // CP-2FA-481
        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            auditoria.registrarRegistroDosFactoresAgotado(CONTEXTO);

            assertThat(eventos(logs, "REGISTRO_DOS_FACTORES_AGOTADO")).singleElement().satisfies(evento -> {
                assertThat(evento.getLevel()).isEqualTo(Level.WARN);
                assertThat(evento.getFormattedMessage()).contains("ip=203.0.113.7", "userAgent=Mozilla/5.0 (JUnit)",
                        "correlationId=" + ID_CORRELACION).doesNotContain("email=", "userId=");
            });
        }
    }

    @Test
    void registrarAgotado_elUserAgentSeSanea() { // CP-2FA-482
        ContextoPeticion malicioso = new ContextoPeticion("203.0.113.7", "Mozilla\r\nREGISTRO_DOS_FACTORES_AGOTADO falso");
        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            auditoria.registrarRegistroDosFactoresAgotado(malicioso);

            assertThat(eventos(logs, "REGISTRO_DOS_FACTORES_AGOTADO")).hasSize(1).allSatisfy(evento ->
                    assertThat(evento.getFormattedMessage()).doesNotContain("\n", "\r"));
        }
    }

    @Test
    void losTresEventos_noSeEmitenConOtroNombreNiConElNombreDeOtroEvento() { // CP-2FA-483
        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            auditoria.registrarRegistroDosFactoresCompletado(CONTEXTO, EMAIL, ID_USUARIO);
            auditoria.registrarRegistroDosFactoresFallido(CONTEXTO, EMAIL, MotivoFalloDosFactores.CODIGO_INCORRECTO);
            auditoria.registrarRegistroDosFactoresAgotado(CONTEXTO);

            // Cada uno aparece una vez y ninguno se mezcla con el evento de la fase A (REGISTRO_DOS_FACTORES_INICIADO)
            assertThat(eventos(logs, "REGISTRO_DOS_FACTORES_")).hasSize(3);
            assertThat(eventos(logs, "REGISTRO_DOS_FACTORES_INICIADO")).isEmpty();
        }
    }
}
