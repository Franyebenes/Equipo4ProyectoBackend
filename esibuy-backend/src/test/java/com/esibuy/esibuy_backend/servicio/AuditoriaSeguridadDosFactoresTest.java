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
 * Eventos de auditoria del segundo factor (HdU 2FA, fase 2). Casos: CP-2FA-226 a CP-2FA-233. Mismo formato que el resto:
 * una linea por evento con una palabra clave y datos clave=valor (ip, userAgent, correlationId y, cuando se conoce, el
 * correo siempre enmascarado), con el user agent saneado. Eventos:
 *  - DOS_FACTORES_ACTIVACION_INICIADA (INFO): la persona ha pedido el QR; con el correo enmascarado y el userId.
 *  - DOS_FACTORES_ACTIVADO (INFO).
 *  - DOS_FACTORES_CODIGOS_REGENERADOS (INFO).
 *  - ALERTA_SEGURIDAD tipo=CODIGO_RECUPERACION_USADO (WARN): con cuantos codigos le quedan, nunca el codigo.
 *  - DOS_FACTORES_FALLIDO (WARN): con el motivo (contrasena incorrecta, codigo incorrecto, codigo reutilizado o
 *    activacion no vigente).
 *  - DOS_FACTORES_LIMITADO (WARN): la cuenta o la IP agoto sus fallos; solo el userId, porque ocurre antes de cargar la
 *    cuenta.
 * Ningun evento incluye la contrasena, el codigo, el secreto ni los codigos de recuperacion.
 *
 * Terminada: ya no lleva la etiqueta pendiente-2fa y entra en la regresion.
 */
class AuditoriaSeguridadDosFactoresTest {

    private static final String ID_CORRELACION = "corr-2fa-123";
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

    private static List<ILoggingEvent> eventosDeDosFactores(CapturadorLogs logs) {
        return logs.eventos().stream().filter(evento -> evento.getFormattedMessage().contains("DOS_FACTORES_")
                || evento.getFormattedMessage().contains("CODIGO_RECUPERACION_USADO")).toList();
    }

    private static String correoEnmascarado() {
        return EnmascaradorDatosLog.correo(EMAIL);
    }

    private static void comprobarDatosDeLaPeticion(ILoggingEvent evento) {
        assertThat(evento.getFormattedMessage()).contains("ip=203.0.113.7", "userAgent=Mozilla/5.0 (JUnit)",
                "correlationId=" + ID_CORRELACION, "email=" + correoEnmascarado()).doesNotContain(EMAIL);
    }

    @Test
    void registrarActivacionIniciada_emiteEventoInformativoConElIdDelUsuario() { // CP-2FA-226
        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            auditoria.registrarDosFactoresActivacionIniciada(CONTEXTO, EMAIL, ID_USUARIO);

            assertThat(eventosDeDosFactores(logs)).singleElement().satisfies(evento -> {
                assertThat(evento.getLevel()).isEqualTo(Level.INFO);
                assertThat(evento.getFormattedMessage()).contains("DOS_FACTORES_ACTIVACION_INICIADA",
                        "userId=" + ID_USUARIO);
                comprobarDatosDeLaPeticion(evento);
            });
        }
    }

    @Test
    void registrarActivado_emiteEventoInformativoConElIdDelUsuario() { // CP-2FA-227
        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            auditoria.registrarDosFactoresActivado(CONTEXTO, EMAIL, ID_USUARIO);

            assertThat(eventosDeDosFactores(logs)).singleElement().satisfies(evento -> {
                assertThat(evento.getLevel()).isEqualTo(Level.INFO);
                assertThat(evento.getFormattedMessage()).contains("DOS_FACTORES_ACTIVADO", "userId=" + ID_USUARIO);
                comprobarDatosDeLaPeticion(evento);
            });
        }
    }

    @Test
    void registrarCodigosRegenerados_emiteEventoInformativo() { // CP-2FA-229
        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            auditoria.registrarDosFactoresCodigosRegenerados(CONTEXTO, EMAIL, ID_USUARIO);

            assertThat(eventosDeDosFactores(logs)).singleElement().satisfies(evento -> {
                assertThat(evento.getLevel()).isEqualTo(Level.INFO);
                assertThat(evento.getFormattedMessage()).contains("DOS_FACTORES_CODIGOS_REGENERADOS",
                        "userId=" + ID_USUARIO);
                comprobarDatosDeLaPeticion(evento);
            });
        }
    }

    @Test
    void registrarCodigoRecuperacionUsado_esUnaAlertaConLosCodigosQueQuedan() { // CP-2FA-230
        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            auditoria.registrarDosFactoresCodigoRecuperacionUsado(CONTEXTO, EMAIL, ID_USUARIO, 7);

            assertThat(eventosDeDosFactores(logs)).singleElement().satisfies(evento -> {
                assertThat(evento.getLevel()).isEqualTo(Level.WARN);
                assertThat(evento.getFormattedMessage()).contains("ALERTA_SEGURIDAD",
                        "tipo=CODIGO_RECUPERACION_USADO", "userId=" + ID_USUARIO, "quedan=7");
                comprobarDatosDeLaPeticion(evento);
            });
        }
    }

    @ParameterizedTest(name = "motivo {0}")
    @EnumSource(MotivoFalloDosFactores.class)
    void registrarFallido_emiteUnAvisoConElMotivo(MotivoFalloDosFactores motivo) { // CP-2FA-231
        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            auditoria.registrarDosFactoresFallido(CONTEXTO, EMAIL, ID_USUARIO, motivo);

            assertThat(eventosDeDosFactores(logs)).singleElement().satisfies(evento -> {
                assertThat(evento.getLevel()).isEqualTo(Level.WARN);
                assertThat(evento.getFormattedMessage()).contains("DOS_FACTORES_FALLIDO", "motivo=" + motivo.name(),
                        "userId=" + ID_USUARIO);
                comprobarDatosDeLaPeticion(evento);
            });
        }
    }

    @Test
    void motivosDeFallo_sonLosCuatroEsperados() { // CP-2FA-231
        assertThat(MotivoFalloDosFactores.values()).extracting(Enum::name).containsExactlyInAnyOrder(
                "CONTRASENA_INCORRECTA", "CODIGO_INCORRECTO", "CODIGO_REUTILIZADO", "ACTIVACION_NO_VIGENTE");
    }

    @Test
    void registrarLimitado_emiteUnAvisoSoloConElIdPorqueLaCuentaTodaviaNoSeHaCargado() { // CP-2FA-232
        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            auditoria.registrarDosFactoresLimitado(CONTEXTO, ID_USUARIO);

            assertThat(eventosDeDosFactores(logs)).singleElement().satisfies(evento -> {
                assertThat(evento.getLevel()).isEqualTo(Level.WARN);
                assertThat(evento.getFormattedMessage()).contains("DOS_FACTORES_LIMITADO", "userId=" + ID_USUARIO,
                        "ip=203.0.113.7", "correlationId=" + ID_CORRELACION);
            });
        }
    }

    @Test
    void elUserAgentSeSaneaYElCorreoNoApareceEnClaroEnNingunEvento() { // CP-2FA-233
        ContextoPeticion conAgenteMalicioso = new ContextoPeticion("203.0.113.7", "Mozilla\r\nDOS_FACTORES_ACTIVADO falso");
        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            auditoria.registrarDosFactoresActivado(conAgenteMalicioso, EMAIL, ID_USUARIO);
            auditoria.registrarDosFactoresFallido(conAgenteMalicioso, EMAIL, ID_USUARIO,
                    MotivoFalloDosFactores.CODIGO_INCORRECTO);

            // Un salto de linea en el user agent no puede falsificar otra linea del log
            assertThat(eventosDeDosFactores(logs)).hasSize(2).allSatisfy(evento -> {
                assertThat(evento.getFormattedMessage()).doesNotContain("\n", "\r").doesNotContain(EMAIL);
            });
        }
    }
}
