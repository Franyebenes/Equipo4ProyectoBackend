package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.MDC;

import com.esibuy.esibuy_backend.configuracion.FiltroCorrelacionId;
import com.esibuy.esibuy_backend.dto.SolicitudLoginDTO;
import com.esibuy.esibuy_backend.excepcion.CredencialesInvalidasException;
import com.esibuy.esibuy_backend.excepcion.LoginBloqueadoTemporalmenteException;
import com.esibuy.esibuy_backend.modelo.EstadoUsuario;
import com.esibuy.esibuy_backend.modelo.Rol;
import com.esibuy.esibuy_backend.util.CapturadorLogs;
import com.esibuy.esibuy_backend.util.RelojMutable;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Eventos de auditoría, alertas de seguridad y ausencia de secretos en los logs.
 *
 * Orden TDD (plan de pruebas, sección 8): paso 4.
 * Casos: CP-AUD-01, CP-AUD-03, CP-AUD-05.
 * Colaboradores: CapturadorLogs (ya existente en util) para leer los logs. En CP-AUD-05 se prueba el conjunto real:
 * ServicioAutenticacion, LimitadorIntentosLogin y AuditoriaSeguridad, con repositorio y codificador mockeados
 * (fixture de ServicioAutenticacionBaseTest).
 *
 * Formato que fijan estas pruebas: una línea por evento, con una palabra clave (LOGIN_CORRECTO en INFO,
 * LOGIN_FALLIDO en WARN, ALERTA_SEGURIDAD en WARN o más) y datos en forma clave=valor: ip, userAgent, correlationId
 * (el del MDC de la petición), email (siempre enmascarado), userId o motivo; las alertas llevan además el tipo
 * (BLOQUEO_CUENTA, IP_MODO_ADAPTATIVO) y segundos o usuarios.
 *
 * Desarrollada con el ciclo Red-Green-Refactor: primero el test (Arrange, Act, Assert), después el código
 * mínimo que lo hace pasar. Terminada: ya no lleva la etiqueta pendiente-login y entra en la regresión.
 */
class AuditoriaSeguridadTest extends ServicioAutenticacionBaseTest {

    private static final String ID_CORRELACION = "corr-123";
    private static final String CORREO_ENMASCARADO = "a***@e***.es";
    private static final ContextoPeticion CONTEXTO_AUDITADO = new ContextoPeticion("203.0.113.7", "Mozilla/5.0 (JUnit)");

    private final AuditoriaSeguridad auditoriaReal = new AuditoriaSeguridad();

    @BeforeEach
    void ponerIdDeCorrelacion() {
        MDC.put(FiltroCorrelacionId.CLAVE_MDC, ID_CORRELACION);
    }

    @AfterEach
    void quitarIdDeCorrelacion() {
        MDC.remove(FiltroCorrelacionId.CLAVE_MDC);
    }

    /** Los eventos de auditoría y de alerta capturados (se ignora cualquier otro log del entorno de pruebas). */
    private static List<ILoggingEvent> eventosDeSeguridad(CapturadorLogs logs) {
        return logs.eventos().stream()
                .filter(evento -> evento.getFormattedMessage().contains("LOGIN_")
                        || evento.getFormattedMessage().contains("ALERTA_SEGURIDAD"))
                .toList();
    }

    // ------------------------------------------------------------------ CP-AUD-01

    @Test
    void registrarResultadoLogin_loginCorrecto_emiteEventoConDatosEnmascaradosYElIdDelUsuario() { // CP-AUD-01
        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            // When
            auditoriaReal.registrarLoginCorrecto(CONTEXTO_AUDITADO, EMAIL, ID_USUARIO);

            // Then: un evento informativo con IP, user agent, correlationId, correo enmascarado y el id del usuario
            assertThat(eventosDeSeguridad(logs)).singleElement().satisfies(evento -> {
                assertThat(evento.getLevel()).isEqualTo(Level.INFO);
                assertThat(evento.getFormattedMessage())
                        .contains("LOGIN_CORRECTO", "ip=203.0.113.7", "userAgent=Mozilla/5.0 (JUnit)",
                                "correlationId=" + ID_CORRELACION, "email=" + CORREO_ENMASCARADO,
                                "userId=" + ID_USUARIO)
                        .doesNotContain(EMAIL);
            });
        }
    }

    @ParameterizedTest(name = "motivo {0}")
    @EnumSource(MotivoFalloLogin.class)
    void registrarResultadoLogin_loginFallido_emiteEventoConElMotivoSoloEnElEvento( // CP-AUD-01
            MotivoFalloLogin motivo) {
        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            // When: correo no registrado, contraseña incorrecta, cuenta no activa, roles inválidos o bloqueo
            auditoriaReal.registrarLoginFallido(CONTEXTO_AUDITADO, EMAIL, motivo);

            // Then: un aviso con los mismos datos, el motivo interno, y sin id de usuario
            assertThat(eventosDeSeguridad(logs)).singleElement().satisfies(evento -> {
                assertThat(evento.getLevel()).isEqualTo(Level.WARN);
                assertThat(evento.getFormattedMessage())
                        .contains("LOGIN_FALLIDO", "ip=203.0.113.7", "userAgent=Mozilla/5.0 (JUnit)",
                                "correlationId=" + ID_CORRELACION, "email=" + CORREO_ENMASCARADO,
                                "motivo=" + motivo.name())
                        .doesNotContain(EMAIL, "userId=");
            });
        }
    }

    @Test
    void registrarResultadoLogin_userAgentConSaltosDeLinea_noPermiteFalsificarOtraLineaDeLog() { // CP-AUD-01
        // Given: un user agent controlado por el cliente que intenta añadir una línea falsa
        ContextoPeticion contextoMalicioso =
                new ContextoPeticion("203.0.113.7", "Mozilla\r\n2026-10-06 INFO LOGIN_CORRECTO email=admin");

        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            // When
            auditoriaReal.registrarLoginFallido(contextoMalicioso, EMAIL, MotivoFalloLogin.CONTRASENA_INCORRECTA);

            // Then: sigue siendo un único evento y su mensaje no tiene saltos de línea
            assertThat(eventosDeSeguridad(logs)).singleElement().satisfies(evento ->
                    assertThat(evento.getFormattedMessage()).doesNotContain("\r", "\n"));
        }
    }

    // ------------------------------------------------------------------ CP-AUD-03

    static Stream<Arguments> alertasDeSeguridad() {
        return Stream.of(
                arguments("bloqueo temporal de cuenta",
                        (Consumer<AuditoriaSeguridad>) auditoria ->
                                auditoria.cuentaBloqueada(EMAIL, "203.0.113.7", 30),
                        List.of("ALERTA_SEGURIDAD", "BLOQUEO_CUENTA", "email=" + CORREO_ENMASCARADO,
                                "ip=203.0.113.7", "segundos=30")),
                arguments("IP en modo adaptativo",
                        (Consumer<AuditoriaSeguridad>) auditoria -> auditoria.ipEnModoAdaptativo("203.0.113.7", 20),
                        List.of("ALERTA_SEGURIDAD", "IP_MODO_ADAPTATIVO", "ip=203.0.113.7", "usuarios=20")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("alertasDeSeguridad")
    void alertar_bloqueoDeCuentaOModoAdaptativoDeIp_emiteAlertaDeSeguridad( // CP-AUD-03
            String descripcion, Consumer<AuditoriaSeguridad> accion, List<String> datosEsperados) {
        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            // When
            accion.accept(auditoriaReal);

            // Then: una alerta con la cuenta enmascarada y la IP (posible fuerza bruta o password spraying)
            assertThat(eventosDeSeguridad(logs)).singleElement().satisfies(evento -> {
                assertThat(evento.getLevel().isGreaterOrEqual(Level.WARN)).isTrue();
                assertThat(evento.getFormattedMessage()).contains(datosEsperados).doesNotContain(EMAIL);
            });
        }
    }

    // ------------------------------------------------------------------ CP-AUD-05

    @Test
    void registrarResultadoLogin_loginCorrectoFallidoYBloqueado_losLogsNoContienenSecretos() { // CP-AUD-05
        // Given: el conjunto real; la cuenta existe y la contraseña correcta solo coincide con su hash
        String contrasenaIncorrecta = "Otra-Contrasena-99";
        RelojMutable reloj = new RelojMutable(Instant.parse("2026-10-06T10:00:00Z"), ZoneId.of("Europe/Madrid"));
        LimitadorIntentosLogin limitadorReal =
                new LimitadorIntentosLogin(reloj, mock(Pausador.class), auditoriaReal);
        ServicioAutenticacion servicioReal =
                new ServicioAutenticacion(repositorioUsuario, codificador, limitadorReal, auditoriaReal);
        dadoUsuarioRegistrado(usuario(Rol.CLIENTE, EstadoUsuario.ACTIVO));
        dadaContrasenaCorrecta();

        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            // When: un login correcto
            servicioReal.autenticar(new SolicitudLoginDTO(EMAIL, CONTRASENA), CONTEXTO_AUDITADO);
            // When: cinco fallidos desde IPs distintas, que bloquean la cuenta
            for (int i = 0; i < 5; i++) {
                ContextoPeticion otroOrigen = new ContextoPeticion("203.0.113." + (10 + i), "JUnit");
                SolicitudLoginDTO erronea = new SolicitudLoginDTO(EMAIL, contrasenaIncorrecta);
                assertThrows(CredencialesInvalidasException.class, () -> servicioReal.autenticar(erronea, otroOrigen));
            }
            // When: un intento con la contraseña correcta mientras está bloqueada
            SolicitudLoginDTO correcta = new SolicitudLoginDTO(EMAIL, CONTRASENA);
            assertThrows(LoginBloqueadoTemporalmenteException.class,
                    () -> servicioReal.autenticar(correcta, CONTEXTO_AUDITADO));

            // Then: ninguno de los logs contiene la contraseña, el hash ni el correo completo
            assertThat(logs.textosCompletos()).isNotEmpty().noneSatisfy(texto ->
                    assertThat(texto).containsAnyOf(CONTRASENA, contrasenaIncorrecta, HASH_USUARIO, EMAIL));

            // Then: y sí quedó constancia de los tres tipos de resultado, de la alerta y del motivo del bloqueo
            String mensajes = String.join("\n", eventosDeSeguridad(logs).stream()
                    .map(ILoggingEvent::getFormattedMessage).toList());
            assertThat(mensajes).contains("LOGIN_CORRECTO", "LOGIN_FALLIDO", "motivo=CONTRASENA_INCORRECTA",
                    "ALERTA_SEGURIDAD", "motivo=BLOQUEO_TEMPORAL");
        }
    }
}
