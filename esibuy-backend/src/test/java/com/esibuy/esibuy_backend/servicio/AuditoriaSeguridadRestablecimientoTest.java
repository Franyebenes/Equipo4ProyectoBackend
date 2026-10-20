package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.MDC;

import com.esibuy.esibuy_backend.configuracion.FiltroCorrelacionId;
import com.esibuy.esibuy_backend.excepcion.CodigoError;
import com.esibuy.esibuy_backend.modelo.EstadoUsuario;
import com.esibuy.esibuy_backend.servicio.ResultadoToken.Motivo;
import com.esibuy.esibuy_backend.util.CapturadorLogs;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Eventos de auditoria del restablecimiento de la contrasena (HdU recuperacion de contrasena, fase 4).
 * Casos: CP-RST-50 a CP-RST-57. Mismo formato que el resto: una linea por evento con una palabra clave y datos
 * clave=valor (ip, userAgent, correlationId y, cuando se conoce, el correo siempre enmascarado), con el user agent
 * saneado. Eventos:
 *  - RESTABLECIMIENTO_CORRECTO (INFO): con el correo enmascarado y el userId.
 *  - RESTABLECIMIENTO_TOKEN_INVALIDO (WARN): solo el motivo; no hay correo porque el token no es de nadie.
 *  - ALERTA_SEGURIDAD tipo=TOKEN_RECUPERACION_REUTILIZADO (WARN): alguien uso un enlace ya gastado.
 *  - RESTABLECIMIENTO_CUENTA_NO_APTA (WARN): cuenta que ya no esta activa (o que ya no existe), con su estado.
 *  - RESTABLECIMIENTO_CONTRASENA_RECHAZADA (INFO): con los CODIGOS que incumple, nunca la contrasena.
 *  - RESTABLECIMIENTO_LIMITADO (WARN): la IP agoto sus intentos.
 * Ningun evento incluye la contrasena ni el token.
 *
 * Terminada: ya no lleva la etiqueta pendiente-recuperacion y entra en la regresion.
 */
class AuditoriaSeguridadRestablecimientoTest {

    private static final String ID_CORRELACION = "corr-rst-123";
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

    private static List<ILoggingEvent> eventosDeRestablecimiento(CapturadorLogs logs) {
        return logs.eventos().stream().filter(evento -> evento.getFormattedMessage().contains("RESTABLECIMIENTO_")
                || evento.getFormattedMessage().contains("TOKEN_RECUPERACION_REUTILIZADO")).toList();
    }

    private static String correoEnmascarado() {
        return EnmascaradorDatosLog.correo(EMAIL);
    }

    @Test
    void registrarRestablecimientoCorrecto_emiteEventoInformativoConElIdDelUsuario() { // CP-RST-50
        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            auditoria.registrarRestablecimientoCorrecto(CONTEXTO, EMAIL, ID_USUARIO);

            assertThat(eventosDeRestablecimiento(logs)).singleElement().satisfies(evento -> {
                assertThat(evento.getLevel()).isEqualTo(Level.INFO);
                assertThat(evento.getFormattedMessage())
                        .contains("RESTABLECIMIENTO_CORRECTO", "ip=203.0.113.7", "userAgent=Mozilla/5.0 (JUnit)",
                                "correlationId=" + ID_CORRELACION, "email=" + correoEnmascarado(), "userId=" + ID_USUARIO)
                        .doesNotContain(EMAIL);
            });
        }
    }

    @ParameterizedTest(name = "motivo {0}")
    @EnumSource(Motivo.class)
    void registrarRestablecimientoTokenInvalido_emiteUnAvisoConElMotivoYSinCorreo(Motivo motivo) { // CP-RST-51
        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            auditoria.registrarRestablecimientoTokenInvalido(CONTEXTO, motivo);

            assertThat(eventosDeRestablecimiento(logs)).singleElement().satisfies(evento -> {
                assertThat(evento.getLevel()).isEqualTo(Level.WARN);
                assertThat(evento.getFormattedMessage())
                        .contains("RESTABLECIMIENTO_TOKEN_INVALIDO", "ip=203.0.113.7", "userAgent=Mozilla/5.0 (JUnit)",
                                "correlationId=" + ID_CORRELACION, "motivo=" + motivo.name())
                        .doesNotContain("email=", "userId=");
            });
        }
    }

    @Test
    void registrarRestablecimientoTokenReutilizado_emiteUnaAlertaDeSeguridad() { // CP-RST-52
        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            auditoria.registrarRestablecimientoTokenReutilizado(CONTEXTO);

            assertThat(eventosDeRestablecimiento(logs)).singleElement().satisfies(evento -> {
                assertThat(evento.getLevel()).isEqualTo(Level.WARN);
                assertThat(evento.getFormattedMessage())
                        .contains("ALERTA_SEGURIDAD", "tipo=TOKEN_RECUPERACION_REUTILIZADO", "ip=203.0.113.7",
                                "userAgent=Mozilla/5.0 (JUnit)", "correlationId=" + ID_CORRELACION)
                        .doesNotContain("email=", "userId=");
            });
        }
    }

    @Test
    void registrarRestablecimientoCuentaNoApta_emiteUnAvisoConElEstadoYElCorreoEnmascarado() { // CP-RST-53
        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            auditoria.registrarRestablecimientoCuentaNoApta(CONTEXTO, EMAIL, EstadoUsuario.BLOQUEADO);

            assertThat(eventosDeRestablecimiento(logs)).singleElement().satisfies(evento -> {
                assertThat(evento.getLevel()).isEqualTo(Level.WARN);
                assertThat(evento.getFormattedMessage())
                        .contains("RESTABLECIMIENTO_CUENTA_NO_APTA", "ip=203.0.113.7", "correlationId=" + ID_CORRELACION,
                                "email=" + correoEnmascarado(), "estado=BLOQUEADO")
                        .doesNotContain(EMAIL);
            });
        }
    }

    @Test
    void registrarRestablecimientoCuentaNoApta_cuentaInexistente_loIndicaSinFallar() { // CP-RST-53
        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            auditoria.registrarRestablecimientoCuentaNoApta(CONTEXTO, null, null);

            assertThat(eventosDeRestablecimiento(logs)).singleElement().satisfies(evento ->
                    assertThat(evento.getFormattedMessage())
                            .contains("RESTABLECIMIENTO_CUENTA_NO_APTA", "estado=(inexistente)"));
        }
    }

    @Test
    void registrarRestablecimientoContrasenaRechazada_listaLosCodigosYNuncaLaContrasena() { // CP-RST-54
        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            auditoria.registrarRestablecimientoContrasenaRechazada(CONTEXTO, EMAIL, ID_USUARIO,
                    EnumSet.of(CodigoError.CONTRASENA_SIN_NUMERO, CodigoError.CONTRASENA_CORTA));

            assertThat(eventosDeRestablecimiento(logs)).singleElement().satisfies(evento -> {
                assertThat(evento.getLevel()).isEqualTo(Level.INFO);
                assertThat(evento.getFormattedMessage())
                        .contains("RESTABLECIMIENTO_CONTRASENA_RECHAZADA", "ip=203.0.113.7",
                                "correlationId=" + ID_CORRELACION, "email=" + correoEnmascarado(),
                                "userId=" + ID_USUARIO, "errores=CONTRASENA_CORTA,CONTRASENA_SIN_NUMERO")
                        .doesNotContain(EMAIL);
            });
        }
    }

    @Test
    void registrarRestablecimientoLimitado_emiteUnAvisoSinCorreo() { // CP-RST-55
        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            auditoria.registrarRestablecimientoLimitado(CONTEXTO);

            assertThat(eventosDeRestablecimiento(logs)).singleElement().satisfies(evento -> {
                assertThat(evento.getLevel()).isEqualTo(Level.WARN);
                assertThat(evento.getFormattedMessage())
                        .contains("RESTABLECIMIENTO_LIMITADO", "ip=203.0.113.7", "userAgent=Mozilla/5.0 (JUnit)",
                                "correlationId=" + ID_CORRELACION)
                        .doesNotContain("email=", "userId=");
            });
        }
    }

    @Test
    void registrarRestablecimiento_userAgentConSaltosDeLinea_noPermiteFalsificarOtraLineaDeLog() { // CP-RST-56
        ContextoPeticion malicioso = new ContextoPeticion("203.0.113.7",
                "Mozilla\r\n2026-10-19 INFO RESTABLECIMIENTO_CORRECTO email=admin");

        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            auditoria.registrarRestablecimientoCorrecto(malicioso, EMAIL, ID_USUARIO);
            auditoria.registrarRestablecimientoTokenInvalido(malicioso, Motivo.DESCONOCIDO);
            auditoria.registrarRestablecimientoTokenReutilizado(malicioso);
            auditoria.registrarRestablecimientoLimitado(malicioso);

            assertThat(eventosDeRestablecimiento(logs)).hasSize(4)
                    .allSatisfy(evento -> assertThat(evento.getFormattedMessage()).doesNotContain("\r", "\n"));
        }
    }

    @Test
    void registrarRestablecimiento_conDatosNulos_noFalla() { // CP-RST-57
        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            auditoria.registrarRestablecimientoTokenInvalido(CONTEXTO, null);
            auditoria.registrarRestablecimientoContrasenaRechazada(CONTEXTO, EMAIL, ID_USUARIO, null);

            assertThat(eventosDeRestablecimiento(logs)).hasSize(2);
        }
    }
}
