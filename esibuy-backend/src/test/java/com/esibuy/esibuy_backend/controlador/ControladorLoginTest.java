package com.esibuy.esibuy_backend.controlador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.esibuy.esibuy_backend.configuracion.ConfiguracionAplicacion;
import com.esibuy.esibuy_backend.configuracion.ConfiguracionLogin;
import com.esibuy.esibuy_backend.configuracion.FiltroCaducidadSesion;
import com.esibuy.esibuy_backend.configuracion.FiltroCorrelacionId;
import com.esibuy.esibuy_backend.dto.SolicitudLoginDTO;
import com.esibuy.esibuy_backend.excepcion.CodigoError;
import com.esibuy.esibuy_backend.excepcion.CredencialesInvalidasException;
import com.esibuy.esibuy_backend.excepcion.DatosLoginInvalidosException;
import com.esibuy.esibuy_backend.excepcion.LoginBloqueadoTemporalmenteException;
import com.esibuy.esibuy_backend.excepcion.ServicioNoDisponibleException;
import com.esibuy.esibuy_backend.modelo.Rol;
import com.esibuy.esibuy_backend.seguridad.ConfiguracionSeguridad;
import com.esibuy.esibuy_backend.seguridad.PoliticaSesion;
import com.esibuy.esibuy_backend.servicio.ContextoPeticion;
import com.esibuy.esibuy_backend.servicio.ResultadoAutenticacion;
import com.esibuy.esibuy_backend.servicio.ServicioAutenticacion;
import com.esibuy.esibuy_backend.util.CapturadorLogs;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.HttpSession;

/**
 * Endpoint POST /api/auth/login con la seguridad real y el servicio mockeado. Es distinto de
 * ControladorAuthTest (registro), que no se toca.
 *
 * Orden TDD (plan de pruebas, sección 8): paso 6.
 * Casos: CP-LCT-01, CP-LCT-03, CP-LCT-07, CP-LCT-08, CP-LCT-10, CP-LCT-11, CP-SES-04, CP-SES-08, CP-SES-09.
 * Colaboradores: ServicioAutenticacion con @MockitoBean; seguridad, CSRF, EstablecedorSesion,
 * ManejadorExcepciones y FiltroCorrelacionId reales.
 *
 * Decisiones que fijan estas pruebas (el equipo puede cambiarlas, pero deben cambiar a la vez aquí):
 *  - POST /api/auth/login (JSON con email y contrasena) y GET /api/auth/csrf (devuelve token y headerName).
 *  - 200 con {id, email, nombre, rol, mensaje}; 401 con {"mensaje":"Credenciales invalidas"}; 400 con
 *    {"errores": {campo: [codigos]}}; 429 con Retry-After; 503 y 500 con correlationId.
 *  - El login es público pero exige token CSRF; el registro sigue exento (lo comprueba ControladorAuthTest).
 *  - Se rechazan las credenciales en la URL (400) y cualquier método distinto de POST en el login (405).
 *  - La sesión autenticada que crea el login es la que usa Spring Security (ya no es una API sin sesión), y un
 *    filtro (FiltroCaducidadSesion) invalida con 401 las sesiones autenticadas que superan su caducidad absoluta;
 *    no toca las sesiones sin contexto de seguridad (p. ej. una anónima que solo guarda el token CSRF).
 *
 * Terminada: ya no lleva la etiqueta pendiente-login y entra en la regresión.
 */
@WebMvcTest(ControladorLogin.class)
@Import({ConfiguracionSeguridad.class, ConfiguracionAplicacion.class, ConfiguracionLogin.class})
class ControladorLoginTest {

    private static final String RUTA_LOGIN = "/api/auth/login";
    private static final String RUTA_CSRF = "/api/auth/csrf";
    private static final String ID_USUARIO = "665f1c2e9b1e8a3d4c5b6a79";
    private static final String EMAIL = "ana.garcia@ejemplo.es";
    private static final String CONTRASENA = "Tren-Azul-Lluvia-77";
    private static final String IP_CLIENTE = "203.0.113.7";
    private static final String USER_AGENT = "JUnit-UA";
    private static final ResultadoAutenticacion RESULTADO = new ResultadoAutenticacion(ID_USUARIO, EMAIL, "Ana",
            Rol.CLIENTE);
    private static final Clock RELOJ_FIJO = Clock.fixed(Instant.parse("2026-10-06T10:00:00Z"),
            ZoneId.of("Europe/Madrid"));

    @Autowired
    private WebApplicationContext contexto;
    @MockitoBean
    private ServicioAutenticacion servicioAutenticacion;

    private MockMvc mockMvc;

    @BeforeEach
    void prepararMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(contexto)
                .addFilters(new FiltroCorrelacionId())
                .apply(springSecurity())
                .build();
    }

    // ------------------------------------------------------------------ utilidades

    private static String cuerpoLogin(String email, String contrasena) {
        return "{\"email\":\"" + email + "\",\"contrasena\":\"" + contrasena + "\"}";
    }

    private static RequestPostProcessor desdeLaIp(String ip) {
        return peticion -> {
            peticion.setRemoteAddr(ip);
            return peticion;
        };
    }

    /** POST al login sin token CSRF. */
    private static MockHttpServletRequestBuilder postSinCsrf(String ruta, MediaType tipo, String cuerpo) {
        return post(ruta).with(desdeLaIp(IP_CLIENTE)).header(HttpHeaders.USER_AGENT, USER_AGENT)
                .contentType(tipo).content(cuerpo);
    }

    /** POST de login con JSON y un token CSRF válido. */
    private static MockHttpServletRequestBuilder loginJson(String cuerpo) {
        return postSinCsrf(RUTA_LOGIN, MediaType.APPLICATION_JSON, cuerpo).with(csrf());
    }

    private static String cuerpoDe(MvcResult resultado) throws Exception {
        return resultado.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ CP-LCT-01

    @Test
    void login_peticionValida_devuelve200ConDatosPublicosSinSecretos() throws Exception { // CP-LCT-01
        // Given
        when(servicioAutenticacion.autenticar(any(), any())).thenReturn(RESULTADO);

        // When
        MvcResult resultado = mockMvc.perform(loginJson(cuerpoLogin(EMAIL, CONTRASENA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ID_USUARIO))
                .andExpect(jsonPath("$.email").value(EMAIL))
                .andExpect(jsonPath("$.nombre").value("Ana"))
                .andExpect(jsonPath("$.rol").value("CLIENTE"))
                .andExpect(jsonPath("$.mensaje").isNotEmpty())
                .andReturn();

        // Then: exactamente esos campos, sin hash, contraseña ni identificador de sesión
        String cuerpo = cuerpoDe(resultado);
        Map<String, Object> campos = JsonPath.read(cuerpo, "$");
        assertThat(campos.keySet()).containsExactlyInAnyOrder("id", "email", "nombre", "rol", "mensaje");
        HttpSession sesion = resultado.getRequest().getSession(false);
        assertThat(sesion).isNotNull();
        assertThat(cuerpo).doesNotContain(CONTRASENA).doesNotContain("JSESSIONID");

        // Then: el servicio recibió las credenciales y el origen (IP y user agent) de la petición
        verify(servicioAutenticacion).autenticar(new SolicitudLoginDTO(EMAIL, CONTRASENA),
                new ContextoPeticion(IP_CLIENTE, USER_AGENT));

        // Then: la sesión creada es la autenticada, con el rol del usuario
        SecurityContext contextoSeguridad = (SecurityContext) sesion
                .getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(contextoSeguridad.getAuthentication().getAuthorities())
                .extracting(autoridad -> autoridad.getAuthority()).containsExactly("ROLE_CLIENTE");
    }

    // ------------------------------------------------------------------ CP-LCT-03

    static Stream<Arguments> excepcionesDeAutenticacion() {
        return Stream.of(
                arguments("credenciales inválidas", new CredencialesInvalidasException(), 401, null,
                        List.of("\"mensaje\":\"Credenciales invalidas\"")),
                arguments("datos inválidos en varios campos",
                        new DatosLoginInvalidosException(Map.of(
                                "email", Set.of(CodigoError.FORMATO_INVALIDO),
                                "contrasena", Set.of(CodigoError.OBLIGATORIO))),
                        400, null,
                        List.of("\"errores\"", "\"email\":[\"FORMATO_INVALIDO\"]", "\"contrasena\":[\"OBLIGATORIO\"]")),
                arguments("bloqueo temporal", new LoginBloqueadoTemporalmenteException(30), 429, "30",
                        List.of("\"mensaje\"")),
                arguments("servicio no disponible", new ServicioNoDisponibleException(null), 503, null,
                        List.of("\"mensaje\"", "\"correlationId\"")));
    }

    @ParameterizedTest(name = "{0} -> {2}")
    @MethodSource("excepcionesDeAutenticacion")
    void login_excepcionesDeAutenticacion_seTraducenARespuestasGenericas( // CP-LCT-03
            String descripcion, RuntimeException excepcion, int estado, String retryAfter,
            List<String> fragmentosEsperados) throws Exception {
        // Given
        when(servicioAutenticacion.autenticar(any(), any())).thenThrow(excepcion);

        // When
        MvcResult resultado = mockMvc.perform(loginJson(cuerpoLogin(EMAIL, CONTRASENA)))
                .andExpect(status().is(estado))
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
                .andReturn();

        // Then: el 429 lleva el tiempo de espera; el resto no; y nunca se repite el correo ni la contraseña
        assertThat(resultado.getResponse().getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo(retryAfter);
        assertThat(cuerpoDe(resultado)).contains(fragmentosEsperados).doesNotContain(EMAIL, CONTRASENA);
    }

    // ------------------------------------------------------------------ CP-LCT-07

    @Test
    void login_excepcionInesperada_devuelve500GenericoConCorrelationId() throws Exception { // CP-LCT-07
        // Given
        String detalleInterno = "Fallo interno al leer la coleccion usuarios";
        when(servicioAutenticacion.autenticar(any(), any())).thenThrow(new IllegalStateException(detalleInterno));

        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            // When
            MvcResult resultado = mockMvc.perform(loginJson(cuerpoLogin(EMAIL, CONTRASENA)))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.mensaje").isNotEmpty())
                    .andExpect(jsonPath("$.correlationId").isNotEmpty())
                    .andReturn();

            // Then: respuesta genérica; el detalle solo en el log, con el mismo correlationId
            String cuerpo = cuerpoDe(resultado);
            String correlationId = JsonPath.read(cuerpo, "$.correlationId");
            assertThat(cuerpo).doesNotContain(detalleInterno).doesNotContain("usuarios");
            assertThat(logs.eventos()).anySatisfy(evento -> {
                assertThat(evento.getThrowableProxy()).isNotNull();
                assertThat(evento.getThrowableProxy().getMessage()).contains(detalleInterno);
                assertThat(evento.getMDCPropertyMap()).containsEntry("correlationId", correlationId);
            });
        }
    }

    // ------------------------------------------------------------------ CP-LCT-08

    @Test
    void login_peticionAnonima_aceptaConTokenCsrfYRechazaSinEl() throws Exception { // CP-LCT-08
        // Given
        when(servicioAutenticacion.autenticar(any(), any())).thenReturn(RESULTADO);
        String cuerpo = cuerpoLogin(EMAIL, CONTRASENA);

        // When / Then: con un token válido, un visitante anónimo llega al servicio (endpoint público)
        mockMvc.perform(loginJson(cuerpo)).andExpect(status().isOk());
        verify(servicioAutenticacion, times(1)).autenticar(any(), any());

        // When / Then: sin token, 403 y el servicio no se invoca (D19)
        mockMvc.perform(postSinCsrf(RUTA_LOGIN, MediaType.APPLICATION_JSON, cuerpo))
                .andExpect(status().isForbidden());

        // When / Then: con un token inválido, también 403
        mockMvc.perform(postSinCsrf(RUTA_LOGIN, MediaType.APPLICATION_JSON, cuerpo).with(csrf().useInvalidToken()))
                .andExpect(status().isForbidden());
        verify(servicioAutenticacion, times(1)).autenticar(any(), any());
    }

    // ------------------------------------------------------------------ CP-LCT-10

    @Test
    void obtenerTokenCsrf_visitanteSinAutenticar_estaDisponible() throws Exception { // CP-LCT-10
        // When: un visitante sin autenticar pide el token
        MvcResult tokenPedido = mockMvc.perform(get(RUTA_CSRF).with(desdeLaIp(IP_CLIENTE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.headerName").isNotEmpty())
                .andReturn();

        // Then: con ese token (y la sesión o cookies que haya recibido) puede enviar el login
        when(servicioAutenticacion.autenticar(any(), any())).thenReturn(RESULTADO);
        String respuesta = cuerpoDe(tokenPedido);
        String token = JsonPath.read(respuesta, "$.token");
        String cabecera = JsonPath.read(respuesta, "$.headerName");
        MockHttpServletRequestBuilder login = postSinCsrf(RUTA_LOGIN, MediaType.APPLICATION_JSON,
                cuerpoLogin(EMAIL, CONTRASENA)).header(cabecera, token);
        if (tokenPedido.getResponse().getCookies().length > 0) {
            login.cookie(tokenPedido.getResponse().getCookies());
        }
        HttpSession sesionPrevia = tokenPedido.getRequest().getSession(false);
        if (sesionPrevia != null) {
            login.session((MockHttpSession) sesionPrevia);
        }
        mockMvc.perform(login).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ CP-LCT-11

    static Stream<Arguments> peticionesNoAceptables() {
        String credenciales = cuerpoLogin(EMAIL, CONTRASENA);
        String json = MediaType.APPLICATION_JSON_VALUE;
        return Stream.of(
                arguments("campo extra: roles", jsonCon("\"roles\":[\"ADMIN\"]"), 400),
                arguments("campo extra: rol", jsonCon("\"rol\":\"ADMIN\""), 400),
                arguments("campo extra: status", jsonCon("\"status\":\"ACTIVO\""), 400),
                arguments("campo extra: recordarme", jsonCon("\"recordarme\":true"), 400),
                arguments("campo $where", jsonCon("\"$where\":\"sleep(5000)\""), 400),
                arguments("clave con punto", jsonCon("\"a.b\":\"x\""), 400),
                arguments("correo como objeto con $ne", enJson("{\"email\":{\"$ne\":null},\"contrasena\":\"x\"}"), 400),
                arguments("contraseña como objeto con $gt",
                        enJson("{\"email\":\"" + EMAIL + "\",\"contrasena\":{\"$gt\":\"\"}}"), 400),
                arguments("sin cuerpo", enJson(""), 400),
                arguments("JSON mal formado", enJson("{\"email\":\"ana"), 400),
                arguments("Content-Type text/plain",
                        postSinCsrf(RUTA_LOGIN, MediaType.TEXT_PLAIN, credenciales).with(csrf()), 415),
                arguments("Content-Type de formulario", postSinCsrf(RUTA_LOGIN, MediaType.APPLICATION_FORM_URLENCODED,
                        "email=" + EMAIL + "&contrasena=" + CONTRASENA).with(csrf()), 415),
                arguments("cuerpo desproporcionado",
                        enJson(cuerpoLogin(EMAIL, "a".repeat(20_000))), 413),
                arguments("credenciales en la URL", postSinCsrf(
                        RUTA_LOGIN + "?email=" + EMAIL + "&contrasena=" + CONTRASENA,
                        MediaType.valueOf(json), credenciales).with(csrf()), 400),
                arguments("petición GET", get(RUTA_LOGIN).with(desdeLaIp(IP_CLIENTE)), 405));
    }

    private static MockHttpServletRequestBuilder enJson(String cuerpo) {
        return loginJson(cuerpo);
    }

    /** Credenciales válidas con una clave más en el JSON. */
    private static MockHttpServletRequestBuilder jsonCon(String claveExtra) {
        return loginJson("{\"email\":\"" + EMAIL + "\",\"contrasena\":\"" + CONTRASENA + "\"," + claveExtra + "}");
    }

    @ParameterizedTest(name = "{0} -> {2}")
    @MethodSource("peticionesNoAceptables")
    void login_peticionesNoAceptables_seRechazanAntesDelServicio( // CP-LCT-11
            String descripcion, MockHttpServletRequestBuilder peticion, int estado) throws Exception {
        // When
        MvcResult resultado = mockMvc.perform(peticion).andExpect(status().is(estado)).andReturn();

        // Then: sin trazas ni nombres de clases, sin sesión y sin haber llegado al servicio
        assertThat(cuerpoDe(resultado)).doesNotContain("Exception", "com.esibuy", "org.springframework", "at java");
        assertThat(resultado.getRequest().getSession(false)).isNull();
        verifyNoInteractions(servicioAutenticacion);
    }

    // ------------------------------------------------------------------ CP-SES-04

    @ParameterizedTest(name = "{0}")
    @MethodSource("excepcionesDeAutenticacion")
    void login_fallido_noCreaNingunaSesion( // CP-SES-04
            String descripcion, RuntimeException excepcion) throws Exception {
        // Given: un login fallido (400, 401, 429 o 503)
        when(servicioAutenticacion.autenticar(any(), any())).thenThrow(excepcion);

        // When
        MvcResult resultado = mockMvc.perform(loginJson(cuerpoLogin(EMAIL, CONTRASENA))).andReturn();

        // Then
        assertThat(resultado.getRequest().getSession(false)).isNull();
    }

    // ------------------------------------------------------------------ CP-SES-08

    private MockMvc mockMvcConFiltroDeCaducidad() {
        return MockMvcBuilders.webAppContextSetup(contexto)
                .addFilters(new FiltroCorrelacionId(), new FiltroCaducidadSesion(new PoliticaSesion(), RELOJ_FIJO))
                .apply(springSecurity())
                .build();
    }

    private static MockHttpSession sesionAutenticadaIniciadaHace(Duration antiguedad) {
        MockHttpSession sesion = new MockHttpSession();
        sesion.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(
                        ID_USUARIO, null, List.of(new SimpleGrantedAuthority("ROLE_CLIENTE")))));
        sesion.setAttribute(PoliticaSesion.ATRIBUTO_INICIO, RELOJ_FIJO.instant().minus(antiguedad));
        sesion.setAttribute(PoliticaSesion.ATRIBUTO_ROL, Rol.CLIENTE);
        return sesion;
    }

    @Test
    void peticion_sesionCaducadaPorLimiteAbsoluto_devuelve401YInvalidaLaSesion() throws Exception { // CP-SES-08
        // Given: una sesión de cliente iniciada hace 8 horas (su límite absoluto)
        MockHttpSession sesion = sesionAutenticadaIniciadaHace(Duration.ofHours(8));

        // When
        mockMvcConFiltroDeCaducidad().perform(get("/api/pedidos").session(sesion))
                .andExpect(status().isUnauthorized());

        // Then
        assertThat(sesion.isInvalid()).isTrue();
    }

    @Test
    void peticion_sesionDentroDelLimiteAbsoluto_siguePudiendoUsarse() throws Exception { // CP-SES-08
        // Given: una sesión de cliente iniciada hace una hora
        MockHttpSession sesion = sesionAutenticadaIniciadaHace(Duration.ofHours(1));

        // When
        int estado = mockMvcConFiltroDeCaducidad().perform(get("/api/pedidos").session(sesion))
                .andReturn().getResponse().getStatus();

        // Then: Spring Security la acepta (la ruta no existe, pero no es ni 401 ni 403) y no se invalida
        assertThat(estado).isNotIn(401, 403);
        assertThat(sesion.isInvalid()).isFalse();
    }

    @Test
    void peticion_sesionAnonimaSinContextoDeSeguridad_noSeInvalida() throws Exception { // CP-SES-08
        // Given: una sesión anónima, p. ej. la que solo guarda el token CSRF
        MockHttpSession sesion = new MockHttpSession();
        sesion.setAttribute("token-csrf", "valor");

        // When
        int estado = mockMvcConFiltroDeCaducidad().perform(get("/api/public/algo").session(sesion))
                .andReturn().getResponse().getStatus();

        // Then: el filtro no la toca
        assertThat(estado).isNotEqualTo(401);
        assertThat(sesion.isInvalid()).isFalse();
    }

    // ------------------------------------------------------------------ CP-SES-09

    @Test
    void login_peticionSegura_incluyeCabecerasDeSeguridadYNoWwwAuthenticate() throws Exception { // CP-SES-09
        // Given: una petición HTTPS (simulada) con login correcto y otra con credenciales inválidas
        when(servicioAutenticacion.autenticar(any(), any())).thenReturn(RESULTADO);
        MvcResult correcto = mockMvc.perform(loginJson(cuerpoLogin(EMAIL, CONTRASENA)).secure(true))
                .andExpect(status().isOk()).andReturn();
        when(servicioAutenticacion.autenticar(any(), any())).thenThrow(new CredencialesInvalidasException());
        MvcResult rechazado = mockMvc.perform(loginJson(cuerpoLogin(EMAIL, CONTRASENA)).secure(true))
                .andExpect(status().isUnauthorized()).andReturn();

        // Then: en ambas, sin caché, con HSTS y sin WWW-Authenticate
        for (MvcResult resultado : List.of(correcto, rechazado)) {
            assertThat(resultado.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
            assertThat(resultado.getResponse().getHeader(HttpHeaders.PRAGMA)).isEqualTo("no-cache");
            assertThat(resultado.getResponse().getHeader("Strict-Transport-Security"))
                    .contains("max-age=31536000", "includeSubDomains");
            assertThat(resultado.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).isNull();
        }
    }
}
