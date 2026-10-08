package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.springframework.test.util.ReflectionTestUtils;

import com.esibuy.esibuy_backend.dto.SolicitudLoginDTO;
import com.esibuy.esibuy_backend.excepcion.CredencialesInvalidasException;
import com.esibuy.esibuy_backend.excepcion.DatosLoginInvalidosException;
import com.esibuy.esibuy_backend.excepcion.LoginBloqueadoTemporalmenteException;
import com.esibuy.esibuy_backend.excepcion.ServicioNoDisponibleException;
import com.esibuy.esibuy_backend.modelo.EstadoUsuario;
import com.esibuy.esibuy_backend.modelo.Rol;
import com.esibuy.esibuy_backend.modelo.Usuario;
import com.esibuy.esibuy_backend.util.CapturadorLogs;
import com.esibuy.esibuy_backend.util.RelojMutable;

import ch.qos.logback.classic.Level;

/**
 * Credenciales incorrectas, usuario no registrado, cuentas bloqueadas y fallos internos.
 *
 * Orden TDD (plan de pruebas, sección 8): paso 3.
 * Casos: CP-LOG-10, CP-LOG-16, CP-LOG-18, CP-LOG-20, CP-LSG-01, CP-LSG-06, CP-BLQ-09.
 * Colaboradores: RepositorioUsuario, PasswordEncoder, LimitadorIntentosLogin y AuditoriaSeguridad
 * mockeados; Clock fijo. En CP-BLQ-09, limitador real con reloj mutable.
 *
 * Reglas que fijan estas pruebas: solo una cuenta ACTIVA puede entrar (BLOQUEADO, DESACTIVADO o sin estado se
 * rechazan igual que una contraseña incorrecta); todo rechazo de credenciales es CredencialesInvalidasException y
 * suma un fallo en el limitador; un fallo interno es ServicioNoDisponibleException, sin causa y sin sumar fallo;
 * la contraseña se verifica siempre una sola vez, también con un hash ficticio si el correo no existe.
 *
 * Desarrollada con el ciclo Red-Green-Refactor: primero el test (Arrange, Act, Assert), después el código
 * mínimo que lo hace pasar. Terminada: ya no lleva la etiqueta pendiente-login y entra en la regresión.
 */
class ServicioAutenticacionRechazoTest extends ServicioAutenticacionBaseTest {

    private static final String MENSAJE_GENERICO = "Credenciales invalidas";

    @Captor
    private ArgumentCaptor<String> hashCaptor;

    private ResultadoAutenticacion autenticarConLaContrasena(String contrasena) {
        return servicio.autenticar(new SolicitudLoginDTO(EMAIL, contrasena), CONTEXTO);
    }

    // ------------------------------------------------------------------ CP-LOG-10

    static Stream<Arguments> causasDeRechazoDeCredenciales() {
        return Stream.of(
                arguments("correo no registrado", false, EstadoUsuario.ACTIVO, CONTRASENA),
                arguments("contraseña incorrecta", true, EstadoUsuario.ACTIVO, "Otra-Contrasena-99"),
                arguments("contraseña con otra capitalización", true, EstadoUsuario.ACTIVO, CONTRASENA.toUpperCase()),
                arguments("contraseña con espacios al principio y al final", true, EstadoUsuario.ACTIVO,
                        " " + CONTRASENA + " "),
                arguments("contraseña con forma de operador", true, EstadoUsuario.ACTIVO, "{\"$ne\":\"\"}"),
                arguments("contraseña débil", true, EstadoUsuario.ACTIVO, "123"),
                arguments("cuenta bloqueada con la contraseña correcta", true, EstadoUsuario.BLOQUEADO, CONTRASENA),
                // La cuenta DESACTIVADO con la contraseña correcta ya no es un rechazo genérico: se informa de que
                // está pendiente de activación (ver ServicioAutenticacionCuentaPendienteTest)
                arguments("cuenta sin estado con la contraseña correcta", true, null, CONTRASENA));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("causasDeRechazoDeCredenciales")
    void autenticar_credencialesRechazadas_lanzaSiempreLaMismaExcepcionGenerica( // CP-LOG-10
            String causa, boolean usuarioRegistrado, EstadoUsuario estado, String contrasenaIntroducida) {
        // Given: la causa del rechazo; solo la contraseña de prueba exacta coincide con el hash
        if (usuarioRegistrado) {
            dadoUsuarioRegistrado(usuario(Rol.CLIENTE, estado));
        }
        dadaContrasenaCorrecta();

        // When
        CredencialesInvalidasException excepcion = assertThrows(CredencialesInvalidasException.class,
                () -> autenticarConLaContrasena(contrasenaIntroducida));

        // Then: el mismo mensaje en todos los casos, sin causa y sin el correo ni la contraseña (y nunca un 400)
        assertThat(excepcion.getMessage()).isEqualTo(MENSAJE_GENERICO);
        assertThat(excepcion.getCause()).isNull();
        assertThat(excepcion.getSuppressed()).isEmpty();
        assertThat(excepcion.getMessage()).doesNotContain(EMAIL, contrasenaIntroducida);
    }

    // ------------------------------------------------------------------ CP-LOG-16

    static Stream<Arguments> rolesInvalidosEnElDocumento() {
        return Stream.of(
                arguments("roles vacío", List.of()),
                arguments("roles ausente", null),
                arguments("más de un rol", List.of(Rol.CLIENTE, Rol.ADMIN)),
                // Un valor que no corresponde a ningún rol conocido llega como elemento nulo
                arguments("rol desconocido", Arrays.asList((Rol) null)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rolesInvalidosEnElDocumento")
    void autenticar_documentoConRolesInvalidos_fallaCerradoYRegistraElProblema( // CP-LOG-16
            String descripcion, List<Rol> roles) {
        // Given: credenciales correctas y cuenta activa, pero con los roles mal guardados
        Usuario usuario = usuario(Rol.CLIENTE, EstadoUsuario.ACTIVO);
        ReflectionTestUtils.setField(usuario, "roles", roles);
        dadoUsuarioRegistrado(usuario);
        dadaContrasenaCorrecta();

        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            // When / Then: no autentica y no es un error interno
            assertThrows(CredencialesInvalidasException.class, () -> autenticarConLaContrasena(CONTRASENA));

            // Then: el problema queda registrado con el id del usuario y sin datos secretos
            assertThat(logs.eventos()).anySatisfy(evento -> {
                assertThat(evento.getLevel().isGreaterOrEqual(Level.WARN)).isTrue();
                assertThat(evento.getFormattedMessage()).contains(ID_USUARIO);
            });
            assertThat(logs.textosCompletos()).noneSatisfy(texto ->
                    assertThat(texto).containsAnyOf(CONTRASENA, HASH_USUARIO, EMAIL));
        }
    }

    // ------------------------------------------------------------------ CP-LOG-18

    enum ColaboradorQueFalla { REPOSITORIO, CODIFICADOR }

    @ParameterizedTest(name = "falla el {0}")
    @EnumSource(ColaboradorQueFalla.class)
    void autenticar_falloInternoDuranteLaAutenticacion_fallaCerradoSinErrorInterno( // CP-LOG-18
            ColaboradorQueFalla colaborador) {
        // Given: MongoDB no disponible, o el codificador con una excepción inesperada (con datos que no deben salir)
        RuntimeException fallo = new IllegalStateException("fallo interno con " + CONTRASENA + " y " + HASH_USUARIO);
        if (colaborador == ColaboradorQueFalla.REPOSITORIO) {
            when(repositorioUsuario.buscarPorEmail(EMAIL)).thenThrow(fallo);
        } else {
            dadoUsuarioRegistrado(usuario(Rol.CLIENTE, EstadoUsuario.ACTIVO));
            when(codificador.matches(any(), any())).thenThrow(fallo);
        }

        // When
        ServicioNoDisponibleException excepcion = assertThrows(ServicioNoDisponibleException.class,
                () -> autenticarConLaContrasena(CONTRASENA));

        // Then: sin causa interna ni datos, y un fallo interno no cuenta como intento fallido de credenciales
        assertThat(excepcion.getCause()).isNull();
        assertThat(excepcion.getMessage()).doesNotContain(CONTRASENA, HASH_USUARIO);
        verify(limitador, never()).registrarFallo(any(), any());
        verify(limitador, never()).registrarExito(any());
    }

    // ------------------------------------------------------------------ CP-LOG-20

    static Stream<Arguments> fallosDeCredencialesQueDebenContar() {
        return Stream.of(
                arguments("correo no registrado", false, EstadoUsuario.ACTIVO, CONTRASENA),
                arguments("contraseña incorrecta", true, EstadoUsuario.ACTIVO, "Otra-Contrasena-99"),
                arguments("cuenta bloqueada", true, EstadoUsuario.BLOQUEADO, CONTRASENA));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fallosDeCredencialesQueDebenContar")
    void autenticar_cadaFalloDeCredenciales_registraIntentoFallidoExistaONoElUsuario( // CP-LOG-20
            String causa, boolean usuarioRegistrado, EstadoUsuario estado, String contrasenaIntroducida) {
        // Given
        if (usuarioRegistrado) {
            dadoUsuarioRegistrado(usuario(Rol.CLIENTE, estado));
        }
        dadaContrasenaCorrecta();

        // When: el correo llega sin normalizar
        assertThrows(CredencialesInvalidasException.class, () -> servicio.autenticar(
                new SolicitudLoginDTO("  ANA.Garcia@Ejemplo.ES ", contrasenaIntroducida), CONTEXTO));

        // Then: un intento fallido con el correo normalizado, exista o no el usuario (anti-enumeración)
        verify(limitador).registrarFallo(EMAIL, CONTEXTO.ip());
        verify(limitador, never()).registrarExito(any());
    }

    // ------------------------------------------------------------------ CP-LSG-01

    static Stream<Arguments> rechazosQueDebenCostarLoMismo() {
        return Stream.of(
                arguments("correo no registrado", false, EstadoUsuario.ACTIVO, CONTRASENA),
                arguments("contraseña incorrecta", true, EstadoUsuario.ACTIVO, "Otra-Contrasena-99"),
                arguments("cuenta bloqueada con la contraseña correcta", true, EstadoUsuario.BLOQUEADO, CONTRASENA));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rechazosQueDebenCostarLoMismo")
    void autenticar_rechazoPorCredenciales_verificaElHashExactamenteUnaVez( // CP-LSG-01
            String causa, boolean usuarioRegistrado, EstadoUsuario estado, String contrasenaIntroducida) {
        // Given
        if (usuarioRegistrado) {
            dadoUsuarioRegistrado(usuario(Rol.CLIENTE, estado));
        }
        dadaContrasenaCorrecta();

        // When
        assertThrows(CredencialesInvalidasException.class, () -> autenticarConLaContrasena(contrasenaIntroducida));

        // Then: se verifica la contraseña una sola vez en los tres casos
        verify(codificador, times(1)).matches(eq(contrasenaIntroducida), hashCaptor.capture());
        if (usuarioRegistrado) {
            assertThat(hashCaptor.getValue()).isEqualTo(HASH_USUARIO);
        } else {
            // Con un correo inexistente se usa un hash ficticio, para que el trabajo sea el mismo (D15)
            assertThat(hashCaptor.getValue()).isNotBlank().isNotEqualTo(HASH_USUARIO);
        }
    }

    // ------------------------------------------------------------------ CP-LSG-06

    private static String textoCompleto(Throwable excepcion) {
        StringWriter texto = new StringWriter();
        excepcion.printStackTrace(new PrintWriter(texto));
        return texto.toString();
    }

    @Test
    void autenticar_cualquierResultado_noExponeSecretosEnDatosNiExcepciones() { // CP-LSG-06
        // Given: las excepciones que puede producir el servicio, con datos secretos a mano para filtrarse
        String correoNoDisponible = "caida@ejemplo.es";
        String correoBloqueado = "bloqueada@ejemplo.es";
        dadoUsuarioRegistrado(usuario(Rol.CLIENTE, EstadoUsuario.ACTIVO));
        doThrow(new IllegalStateException("fallo con " + CONTRASENA + " y " + HASH_USUARIO))
                .when(repositorioUsuario).buscarPorEmail(correoNoDisponible);
        lenient().doThrow(new LoginBloqueadoTemporalmenteException(30)).when(limitador)
                .comprobarIntento(correoBloqueado, CONTEXTO.ip());

        List<Throwable> excepciones = new ArrayList<>();
        excepciones.add(catchThrowable(() -> autenticarConLaContrasena(CONTRASENA)));
        excepciones.add(catchThrowable(() -> servicio.autenticar(
                new SolicitudLoginDTO("no-es-un-correo", CONTRASENA), CONTEXTO)));
        excepciones.add(catchThrowable(() -> servicio.autenticar(
                new SolicitudLoginDTO(correoNoDisponible, CONTRASENA), CONTEXTO)));
        excepciones.add(catchThrowable(() -> servicio.autenticar(
                new SolicitudLoginDTO(correoBloqueado, CONTRASENA), CONTEXTO)));

        // Then: son las cuatro esperadas y ninguna lleva la contraseña ni el hash en mensaje, causa ni traza
        assertThat(excepciones).hasSize(4).hasOnlyElementsOfTypes(CredencialesInvalidasException.class,
                DatosLoginInvalidosException.class, ServicioNoDisponibleException.class,
                LoginBloqueadoTemporalmenteException.class);
        assertThat(excepciones).allSatisfy(excepcion ->
                assertThat(textoCompleto(excepcion)).doesNotContain(CONTRASENA, HASH_USUARIO));

        // Then: ni la solicitud en el log ni el resultado exponen la contraseña, el hash o el estado interno
        assertThat(new SolicitudLoginDTO(EMAIL, CONTRASENA).toString()).doesNotContain(CONTRASENA);
        assertThat(Arrays.stream(ResultadoAutenticacion.class.getRecordComponents())
                .map(componente -> componente.getName()))
                .containsExactlyInAnyOrder("id", "email", "nombre", "rol");
    }

    // ------------------------------------------------------------------ CP-BLQ-09

    @Test
    void autenticar_cuentaBloqueadaTemporalmente_rechazaCredencialesCorrectasSinConsultarNada() { // CP-BLQ-09
        // Given: limitador real con reloj mutable y la cuenta bloqueada por 5 fallos (30 segundos)
        RelojMutable reloj = new RelojMutable(Instant.parse("2026-10-06T10:00:00Z"), ZoneId.of("Europe/Madrid"));
        LimitadorIntentosLogin limitadorReal =
                new LimitadorIntentosLogin(reloj, mock(Pausador.class), mock(AlertasSeguridad.class));
        ServicioAutenticacion servicioConLimitadorReal =
                new ServicioAutenticacion(repositorioUsuario, codificador, limitadorReal, auditoria, reloj);
        for (int i = 0; i < 5; i++) {
            limitadorReal.registrarFallo(EMAIL, CONTEXTO.ip());
        }
        SolicitudLoginDTO credencialesCorrectas = new SolicitudLoginDTO(EMAIL, CONTRASENA);

        // When / Then: se rechaza con el tiempo restante, sin consultar ni el repositorio ni el codificador
        LoginBloqueadoTemporalmenteException bloqueo = assertThrows(LoginBloqueadoTemporalmenteException.class,
                () -> servicioConLimitadorReal.autenticar(credencialesCorrectas, CONTEXTO));
        assertThat(bloqueo.getSegundosRestantes()).isEqualTo(30);
        verifyNoInteractions(repositorioUsuario, codificador);

        // Then: el intento rechazado no prolonga el bloqueo: diez segundos después faltan 20
        reloj.avanzar(Duration.ofSeconds(10));
        bloqueo = assertThrows(LoginBloqueadoTemporalmenteException.class,
                () -> servicioConLimitadorReal.autenticar(credencialesCorrectas, CONTEXTO));
        assertThat(bloqueo.getSegundosRestantes()).isEqualTo(20);
        assertThat(limitadorReal.fallosConsecutivos(EMAIL)).isEqualTo(5);

        // Then: pasados los 30 segundos de origen, queda libre
        reloj.avanzar(Duration.ofSeconds(20));
        assertThatCode(() -> limitadorReal.comprobarIntento(EMAIL, CONTEXTO.ip())).doesNotThrowAnyException();
    }
}
