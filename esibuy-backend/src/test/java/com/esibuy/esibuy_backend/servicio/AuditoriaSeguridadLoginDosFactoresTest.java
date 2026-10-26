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
 * Eventos de auditoria del login en dos pasos (HdU 2FA, fase 3). Casos: CP-2FA-329 a CP-2FA-333. Mismo formato que el
 * resto: una linea por evento con una palabra clave y datos clave=valor (ip, userAgent, correlationId y, cuando se conoce,
 * el correo siempre enmascarado), con el user agent saneado. Eventos:
 *  - LOGIN_PRIMER_FACTOR_CORRECTO (INFO): contrasena correcta de una cuenta con 2FA, a falta del codigo. No es un login
 *    correcto: ese evento (LOGIN_CORRECTO) solo se emite al acertar el codigo.
 *  - LOGIN_SEGUNDO_FACTOR_NO_VIGENTE (WARN): se intento el segundo paso sin un desafio valido (no lo habia, caduco, o la
 *    cuenta cambio). Sin correo ni id: lo normal es que no haya desafio al que atribuirlo.
 *  - LOGIN_SEGUNDO_FACTOR_AGOTADO (WARN): se fallaron todos los intentos de un desafio y se descarto.
 * Ningun evento incluye el codigo, la contrasena ni los secretos.
 *
 * Terminada: ya no lleva la etiqueta pendiente-2fa y entra en la regresion.
 */
class AuditoriaSeguridadLoginDosFactoresTest {

    private static final String ID_CORRELACION = "corr-login2fa-123";
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

    private static List<ILoggingEvent> eventosDelLogin(CapturadorLogs logs) {
        return logs.eventos().stream().filter(evento -> evento.getFormattedMessage().contains("LOGIN_")).toList();
    }

    @Test
    void registrarLoginPrimerFactorCorrecto_emiteEventoInformativoYNoElDeLoginCorrecto() { // CP-2FA-329
        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            auditoria.registrarLoginPrimerFactorCorrecto(CONTEXTO, EMAIL, ID_USUARIO);

            assertThat(eventosDelLogin(logs)).singleElement().satisfies(evento -> {
                assertThat(evento.getLevel()).isEqualTo(Level.INFO);
                assertThat(evento.getFormattedMessage()).contains("LOGIN_PRIMER_FACTOR_CORRECTO", "userId=" + ID_USUARIO,
                        "ip=203.0.113.7", "userAgent=Mozilla/5.0 (JUnit)", "correlationId=" + ID_CORRELACION,
                        "email=" + EnmascaradorDatosLog.correo(EMAIL)).doesNotContain(EMAIL, "LOGIN_CORRECTO");
            });
        }
    }

    @Test
    void registrarLoginSegundoFactorNoVigente_emiteUnAvisoSinCorreoNiUsuario() { // CP-2FA-330
        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            auditoria.registrarLoginSegundoFactorNoVigente(CONTEXTO);

            assertThat(eventosDelLogin(logs)).singleElement().satisfies(evento -> {
                assertThat(evento.getLevel()).isEqualTo(Level.WARN);
                assertThat(evento.getFormattedMessage()).contains("LOGIN_SEGUNDO_FACTOR_NO_VIGENTE", "ip=203.0.113.7",
                        "userAgent=Mozilla/5.0 (JUnit)", "correlationId=" + ID_CORRELACION)
                        .doesNotContain("email=", "userId=");
            });
        }
    }

    @Test
    void registrarLoginSegundoFactorAgotado_emiteUnAvisoConElCorreoEnmascaradoYElId() { // CP-2FA-331
        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            auditoria.registrarLoginSegundoFactorAgotado(CONTEXTO, EMAIL, ID_USUARIO);

            assertThat(eventosDelLogin(logs)).singleElement().satisfies(evento -> {
                assertThat(evento.getLevel()).isEqualTo(Level.WARN);
                assertThat(evento.getFormattedMessage()).contains("LOGIN_SEGUNDO_FACTOR_AGOTADO", "userId=" + ID_USUARIO,
                        "email=" + EnmascaradorDatosLog.correo(EMAIL), "ip=203.0.113.7").doesNotContain(EMAIL);
            });
        }
    }

    @Test
    void elUserAgentSeSaneaEnLosTresEventos() { // CP-2FA-332
        ContextoPeticion malicioso = new ContextoPeticion("203.0.113.7", "Mozilla\r\nLOGIN_CORRECTO falso");
        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            auditoria.registrarLoginPrimerFactorCorrecto(malicioso, EMAIL, ID_USUARIO);
            auditoria.registrarLoginSegundoFactorNoVigente(malicioso);
            auditoria.registrarLoginSegundoFactorAgotado(malicioso, EMAIL, ID_USUARIO);

            // Un salto de linea en el user agent no puede falsificar otra linea del log
            assertThat(eventosDelLogin(logs)).hasSize(3).allSatisfy(evento ->
                    assertThat(evento.getFormattedMessage()).doesNotContain("\n", "\r"));
        }
    }

    @Test
    void ningunEventoLlevaDatosSecretos() { // CP-2FA-333
        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            auditoria.registrarLoginPrimerFactorCorrecto(CONTEXTO, EMAIL, ID_USUARIO);
            auditoria.registrarLoginSegundoFactorNoVigente(CONTEXTO);
            auditoria.registrarLoginSegundoFactorAgotado(CONTEXTO, EMAIL, ID_USUARIO);

            // Los metodos no reciben codigo ni contrasena, asi que no pueden escribirlos: se comprueba el texto completo
            assertThat(eventosDelLogin(logs)).hasSize(3).allSatisfy(evento -> assertThat(evento.getFormattedMessage().toLowerCase())
                    .doesNotContain("contrasena=", "codigo=", "secreto=", "password"));
        }
    }
}
