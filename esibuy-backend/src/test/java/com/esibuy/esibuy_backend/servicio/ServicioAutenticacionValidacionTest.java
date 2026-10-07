package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.esibuy.esibuy_backend.dto.SolicitudLoginDTO;
import com.esibuy.esibuy_backend.excepcion.CodigoError;
import com.esibuy.esibuy_backend.excepcion.DatosLoginInvalidosException;
import com.esibuy.esibuy_backend.repositorio.RepositorioUsuario;
import com.esibuy.esibuy_backend.util.Constantes;

/**
 * Validación y normalización de las credenciales antes de tocar la BBDD.
 *
 * Orden TDD (plan de pruebas, sección 8): paso 1.
 * Casos: CP-LOG-21, CP-LOG-25, CP-LOG-26, CP-LOG-28.
 * Colaboradores: RepositorioUsuario, PasswordEncoder, LimitadorIntentosLogin y AuditoriaSeguridad
 * mockeados; Clock fijo.
 *
 * Desarrollada con el ciclo Red-Green-Refactor: primero el test (Arrange, Act, Assert), después el código
 * mínimo que lo hace pasar. Terminada: ya no lleva la etiqueta @Tag("pendiente-login") y entra en la regresión.
 */
@ExtendWith(MockitoExtension.class)
class ServicioAutenticacionValidacionTest {

    private static final String EMAIL_VALIDO = "ana.garcia@ejemplo.es";
    private static final String CONTRASENA_VALIDA = "Tren-Azul-Lluvia-77";
    /** Longitud máxima de un correo electrónico (RFC 5321). */
    private static final int LONGITUD_MAXIMA_EMAIL = 254;
    private static final String EMOJI = "😀";

    private static final Clock RELOJ_FIJO =
            Clock.fixed(Instant.parse("2026-10-06T10:00:00Z"), ZoneId.of("Europe/Madrid"));
    private static final ContextoPeticion CONTEXTO = new ContextoPeticion("203.0.113.7", "JUnit");

    @Mock
    private RepositorioUsuario repositorioUsuario;
    @Mock
    private PasswordEncoder codificador;
    @Mock
    private LimitadorIntentosLogin limitador;
    @Mock
    private AuditoriaSeguridad auditoria;

    private ServicioAutenticacion servicio;

    @BeforeEach
    void prepararServicio() {
        servicio = new ServicioAutenticacion(repositorioUsuario, codificador, limitador, auditoria, RELOJ_FIJO);
    }

    /** Correo con formato válido y exactamente la longitud pedida. */
    private static String correoDeLongitud(int longitud) {
        String sufijo = "@b.es";
        return "a".repeat(longitud - sufijo.length()) + sufijo;
    }

    // ------------------------------------------------------------------ CP-LOG-21

    static Stream<Arguments> credencialesConCamposObligatoriosVacios() {
        return Stream.of(
                // Solo falta el correo
                arguments(null, CONTRASENA_VALIDA, Set.of("email")),
                arguments("", CONTRASENA_VALIDA, Set.of("email")),
                arguments("   ", CONTRASENA_VALIDA, Set.of("email")),
                arguments("\t\n", CONTRASENA_VALIDA, Set.of("email")),
                // Solo falta la contraseña
                arguments(EMAIL_VALIDO, null, Set.of("contrasena")),
                arguments(EMAIL_VALIDO, "", Set.of("contrasena")),
                arguments(EMAIL_VALIDO, "   ", Set.of("contrasena")),
                // Faltan los dos: se devuelven los dos errores a la vez
                arguments(null, null, Set.of("email", "contrasena")),
                arguments("", "", Set.of("email", "contrasena")),
                arguments("   ", "   ", Set.of("email", "contrasena")));
    }

    @ParameterizedTest(name = "correo=[{0}] contrasena=[{1}] -> obligatorio en {2}")
    @MethodSource("credencialesConCamposObligatoriosVacios")
    void autenticar_camposObligatoriosVacios_devuelveErrorObligatorioEnCadaCampo( // CP-LOG-21
            String email, String contrasena, Set<String> camposAfectados) {
        // Given
        SolicitudLoginDTO solicitud = new SolicitudLoginDTO(email, contrasena);

        // When
        DatosLoginInvalidosException excepcion = assertThrows(DatosLoginInvalidosException.class,
                () -> servicio.autenticar(solicitud, CONTEXTO));

        // Then: OBLIGATORIO en cada campo afectado, y solo en ellos, todos a la vez
        assertThat(excepcion.getErrores()).containsOnlyKeys(camposAfectados);
        assertThat(excepcion.getErrores().values())
                .allSatisfy(codigos -> assertThat(codigos).containsExactly(CodigoError.OBLIGATORIO));
        // Sin consultar repositorio, hash ni contadores: unos datos incompletos no cuentan como intento fallido
        verifyNoInteractions(repositorioUsuario, codificador, limitador);
    }

    // ------------------------------------------------------------------ CP-LOG-25

    @ParameterizedTest(name = "correo no válido: {0}")
    @ValueSource(strings = {
            "sin-arroba",
            "a@",
            "a@@b.com",
            "a b@c.com",
            "@dominio.es",
            "{\"$ne\":null}",
            "{\"$gt\":\"\"}",
            "$ne",
            "<script>alert(1)</script>"})
    void autenticar_correoConFormatoInvalidoOInyeccion_rechazaSinConsultarElRepositorio( // CP-LOG-25
            String correo) {
        // Given: correos mal formados y textos con forma de operador NoSQL o de script
        SolicitudLoginDTO solicitud = new SolicitudLoginDTO(correo, CONTRASENA_VALIDA);

        // When
        DatosLoginInvalidosException excepcion = assertThrows(DatosLoginInvalidosException.class,
                () -> servicio.autenticar(solicitud, CONTEXTO));

        // Then: un único error, de formato, en el correo; y la BBDD ni se entera
        assertThat(excepcion.getErrores()).containsOnlyKeys("email");
        assertThat(excepcion.getErrores().get("email")).containsExactly(CodigoError.FORMATO_INVALIDO);
        verifyNoInteractions(repositorioUsuario);
    }

    // ------------------------------------------------------------------ CP-LOG-26

    static Stream<Arguments> credencialesConLongitudExcesiva() {
        return Stream.of(
                arguments("correo de 255 caracteres (el máximo es 254)",
                        correoDeLongitud(LONGITUD_MAXIMA_EMAIL + 1), CONTRASENA_VALIDA, "email"),
                arguments("contraseña de 129 caracteres (el máximo es 128)",
                        EMAIL_VALIDO, "a".repeat(Constantes.LONGITUD_MAXIMA + 1), "contrasena"),
                // 129 emojis son 258 unidades UTF-16: se cuentan caracteres reales, no unidades
                arguments("contraseña de 129 emojis",
                        EMAIL_VALIDO, EMOJI.repeat(Constantes.LONGITUD_MAXIMA + 1), "contrasena"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("credencialesConLongitudExcesiva")
    void autenticar_longitudesExcesivas_rechazaSinConsultarBbddNiCalcularElHash( // CP-LOG-26
            String descripcion, String email, String contrasena, String campoAfectado) {
        // Given
        SolicitudLoginDTO solicitud = new SolicitudLoginDTO(email, contrasena);

        // When
        DatosLoginInvalidosException excepcion = assertThrows(DatosLoginInvalidosException.class,
                () -> servicio.autenticar(solicitud, CONTEXTO));

        // Then: LONGITUD_EXCESIVA solo en el campo afectado, sin tocar la BBDD ni gastar CPU en el hash
        assertThat(excepcion.getErrores()).containsOnlyKeys(campoAfectado);
        assertThat(excepcion.getErrores().get(campoAfectado)).containsExactly(CodigoError.LONGITUD_EXCESIVA);
        verifyNoInteractions(repositorioUsuario, codificador);
    }

    // ------------------------------------------------------------------ CP-LOG-28

    static Stream<Arguments> contrasenasDeLongitudMaximaExacta() {
        return Stream.of(
                arguments("128 caracteres", "a".repeat(Constantes.LONGITUD_MAXIMA)),
                // 128 emojis son 256 unidades UTF-16: contando caracteres reales están justo en el máximo
                arguments("128 emojis", EMOJI.repeat(Constantes.LONGITUD_MAXIMA)));
    }

    @ParameterizedTest(name = "contraseña de {0}")
    @MethodSource("contrasenasDeLongitudMaximaExacta")
    void autenticar_contrasenaConLongitudMaximaExacta_seProcesaConNormalidad( // CP-LOG-28
            String descripcion, String contrasena) {
        // Given: ningún usuario con ese correo; el resultado normal de la autenticación sería «credenciales
        // inválidas», que es cosa de la fase 3. Lo que importa aquí es que la longitud no la haga rechazar
        SolicitudLoginDTO solicitud = new SolicitudLoginDTO(EMAIL_VALIDO, contrasena);

        // When
        Throwable lanzada = catchThrowable(() -> servicio.autenticar(solicitud, CONTEXTO));

        // Then: no es un rechazo por datos inválidos y la petición llegó a consultar la BBDD
        assertThat(lanzada).isNotInstanceOf(DatosLoginInvalidosException.class);
        verify(repositorioUsuario).buscarPorEmail(EMAIL_VALIDO);
    }
}
