package com.esibuy.esibuy_backend.controlador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
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
import com.esibuy.esibuy_backend.configuracion.FiltroCorrelacionId;
import com.esibuy.esibuy_backend.dto.RespuestaRecuperacionDTO;
import com.esibuy.esibuy_backend.dto.SolicitudRecuperacionDTO;
import com.esibuy.esibuy_backend.excepcion.CodigoError;
import com.esibuy.esibuy_backend.excepcion.DatosRecuperacionInvalidosException;
import com.esibuy.esibuy_backend.excepcion.ServicioNoDisponibleException;
import com.esibuy.esibuy_backend.seguridad.ConfiguracionSeguridad;
import com.esibuy.esibuy_backend.servicio.ContextoPeticion;
import com.esibuy.esibuy_backend.servicio.ServicioRecuperacion;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.HttpSession;

/*
 * Endpoint POST /api/auth/recuperacion con la seguridad real y el servicio mockeado (HdU recuperacion de contrasena,
 * fase 2). Casos: CP-REC-40 a CP-REC-46.
 *
 * Decisiones que fijan estas pruebas (el equipo puede cambiarlas, pero deben cambiar a la vez aqui):
 *  - POST /api/auth/recuperacion con JSON {"email": "..."}. Responde siempre 200 con {"mensaje": "..."} identico: el
 *    controlador no sabe, ni dice, si la cuenta existe.
 *  - Es publico pero exige token CSRF, como el login (solo el registro esta exento).
 *  - 400 con {"errores": {"email": [codigos]}} si el correo esta mal formado; 503 y 500 genericos con correlationId.
 *  - Se rechazan campos desconocidos, el correo en la URL (acabaria en logs), otros tipos de contenido (415), cuerpos
 *    desproporcionados (413) y cualquier metodo distinto de POST (un GET anonimo recibe 401 por la regla general
 *    de la API, no 405: solo el login esta abierto a todos los metodos). Un correo que no sea texto (p. ej. un
 *    numero) lo convierte Jackson a texto y lo rechaza la validacion del servicio (CP-REC-06).
 *  - La IP que recibe el servicio es la del socket; la cabecera X-Forwarded-For solo se respeta desde proxies de
 *    confianza (ResolutorIpCliente, sin ninguno configurado en estas pruebas).
 *  - No crea sesion autenticada.
 *
 * Terminada: ya no lleva la etiqueta pendiente-recuperacion y entra en la regresion.
 */
@WebMvcTest(ControladorRecuperacion.class)
@Import({ConfiguracionSeguridad.class, ConfiguracionAplicacion.class, ConfiguracionLogin.class})
class ControladorRecuperacionTest {

    private static final String RUTA = "/api/auth/recuperacion";
    private static final String EMAIL = "ana.garcia@ejemplo.es";
    private static final String IP_CLIENTE = "203.0.113.7";
    private static final String USER_AGENT = "JUnit-UA";
    private static final SolicitudRecuperacionDTO SOLICITUD = new SolicitudRecuperacionDTO(EMAIL);
    private static final ContextoPeticion CONTEXTO = new ContextoPeticion(IP_CLIENTE, USER_AGENT);

    @Autowired
    private WebApplicationContext contexto;
    @MockitoBean
    private ServicioRecuperacion servicio;

    private MockMvc mockMvc;

    @BeforeEach
    void prepararMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(contexto)
                .addFilters(new FiltroCorrelacionId())
                .apply(springSecurity())
                .build();
    }

    // ------------------------------------------------------------------ utilidades

    private static String cuerpo(String email) {
        return "{\"email\":\"" + email + "\"}";
    }

    private static RequestPostProcessor desdeLaIp(String ip) {
        return peticion -> {
            peticion.setRemoteAddr(ip);
            return peticion;
        };
    }

    private static MockHttpServletRequestBuilder postSinCsrf(String ruta, MediaType tipo, String contenido) {
        return post(ruta).with(desdeLaIp(IP_CLIENTE)).header(HttpHeaders.USER_AGENT, USER_AGENT)
                .contentType(tipo).content(contenido);
    }

    private static MockHttpServletRequestBuilder recuperacion(String contenido) {
        return postSinCsrf(RUTA, MediaType.APPLICATION_JSON, contenido).with(csrf());
    }

    private static String cuerpoDe(MvcResult resultado) throws Exception {
        return resultado.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ CP-REC-40

    @Test
    void solicitar_peticionValida_devuelve200ConElMensajeGenericoYPasaAlServicioElCorreoYElOrigen() throws Exception {
        // CP-REC-40
        // When
        MvcResult resultado = mockMvc.perform(recuperacion(cuerpo(EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mensaje").value(RespuestaRecuperacionDTO.MENSAJE_GENERICO))
                .andReturn();

        // Then: exactamente un campo, sin eco del correo, y el servicio recibe la solicitud y el origen
        Map<String, Object> campos = JsonPath.read(cuerpoDe(resultado), "$");
        assertThat(campos.keySet()).containsExactly("mensaje");
        assertThat(cuerpoDe(resultado)).doesNotContain(EMAIL);
        verify(servicio).solicitar(SOLICITUD, CONTEXTO);
    }

    @Test
    void solicitar_variasPeticiones_dan_siempreLaMismaRespuesta() throws Exception { // CP-REC-40
        // El servicio no devuelve nada, asi que nada de lo que haga por dentro puede cambiar la respuesta
        String primera = cuerpoDe(mockMvc.perform(recuperacion(cuerpo(EMAIL))).andExpect(status().isOk()).andReturn());
        String segunda = cuerpoDe(mockMvc.perform(recuperacion(cuerpo("otra.persona@ejemplo.es")))
                .andExpect(status().isOk()).andReturn());

        assertThat(segunda).isEqualTo(primera);
    }

    // ------------------------------------------------------------------ CP-REC-41

    @Test
    void solicitar_exigeTokenCsrf() throws Exception { // CP-REC-41
        // Con token valido, un visitante anonimo llega al servicio (endpoint publico)
        mockMvc.perform(recuperacion(cuerpo(EMAIL))).andExpect(status().isOk());
        verify(servicio, times(1)).solicitar(any(), any());

        // Sin token, 403 y el servicio no se invoca
        mockMvc.perform(postSinCsrf(RUTA, MediaType.APPLICATION_JSON, cuerpo(EMAIL))).andExpect(status().isForbidden());

        // Con un token invalido, tambien 403
        mockMvc.perform(postSinCsrf(RUTA, MediaType.APPLICATION_JSON, cuerpo(EMAIL)).with(csrf().useInvalidToken()))
                .andExpect(status().isForbidden());
        verify(servicio, times(1)).solicitar(any(), any());
    }

    // ------------------------------------------------------------------ CP-REC-42

    @Test
    void solicitar_correoMalFormado_devuelve400ConElErrorDelCampo() throws Exception { // CP-REC-42
        // Given
        doThrow(new DatosRecuperacionInvalidosException(Map.of("email", Set.of(CodigoError.FORMATO_INVALIDO))))
                .when(servicio).solicitar(any(), any());

        // When
        MvcResult resultado = mockMvc.perform(recuperacion(cuerpo("no-es-un-correo")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores.email[0]").value("FORMATO_INVALIDO"))
                .andReturn();

        // Then: no repite lo que escribio el usuario
        assertThat(cuerpoDe(resultado)).doesNotContain("no-es-un-correo");
    }

    @Test
    void solicitar_servicioNoDisponible_devuelve503ConCorrelationId() throws Exception { // CP-REC-42
        doThrow(new ServicioNoDisponibleException(null)).when(servicio).solicitar(any(), any());

        MvcResult resultado = mockMvc.perform(recuperacion(cuerpo(EMAIL)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.mensaje").isNotEmpty())
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andReturn();

        assertThat(cuerpoDe(resultado)).doesNotContain(EMAIL);
    }

    @Test
    void solicitar_errorInesperado_devuelve500GenericoSinDetalles() throws Exception { // CP-REC-42
        doThrow(new IllegalStateException("fallo interno en tokens_recuperacion")).when(servicio)
                .solicitar(any(), any());

        MvcResult resultado = mockMvc.perform(recuperacion(cuerpo(EMAIL)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andReturn();

        assertThat(cuerpoDe(resultado)).doesNotContain("tokens_recuperacion", EMAIL);
    }

    // ------------------------------------------------------------------ CP-REC-43

    private static MockHttpServletRequestBuilder enJson(String contenido) {
        return recuperacion(contenido);
    }

    private static MockHttpServletRequestBuilder jsonCon(String campoExtra) {
        return recuperacion("{\"email\":\"" + EMAIL + "\"," + campoExtra + "}");
    }

    static Stream<Arguments> peticionesNoAceptables() {
        return Stream.of(
                arguments("campo extra: contrasena", jsonCon("\"contrasena\":\"x\""), 400),
                arguments("campo extra: rol", jsonCon("\"rol\":\"ADMIN\""), 400),
                arguments("campo extra: estado", jsonCon("\"estado\":\"ACTIVO\""), 400),
                arguments("campo extra: token", jsonCon("\"token\":\"abc\""), 400),
                arguments("campo $where", jsonCon("\"$where\":\"sleep(5000)\""), 400),
                arguments("clave con punto", jsonCon("\"a.b\":\"x\""), 400),
                arguments("correo como objeto con $ne", enJson("{\"email\":{\"$ne\":null}}"), 400),
                arguments("correo como lista", enJson("{\"email\":[\"" + EMAIL + "\",\"otro@ejemplo.es\"]}"), 400),
                arguments("sin cuerpo", enJson(""), 400),
                arguments("JSON mal formado", enJson("{\"email\":\"ana"), 400),
                arguments("Content-Type text/plain",
                        postSinCsrf(RUTA, MediaType.TEXT_PLAIN, cuerpo(EMAIL)).with(csrf()), 415),
                arguments("Content-Type de formulario",
                        postSinCsrf(RUTA, MediaType.APPLICATION_FORM_URLENCODED, "email=" + EMAIL).with(csrf()), 415),
                arguments("cuerpo desproporcionado", enJson(cuerpo("a".repeat(20_000))), 413),
                arguments("correo en la URL", postSinCsrf(RUTA + "?email=" + EMAIL, MediaType.APPLICATION_JSON,
                        cuerpo(EMAIL)).with(csrf()), 400),
                arguments("peticion GET (anonima: 401 por la regla general)", get(RUTA).with(desdeLaIp(IP_CLIENTE)), 401));
    }

    @ParameterizedTest(name = "{0} -> {2}")
    @MethodSource("peticionesNoAceptables")
    void solicitar_peticionNoAceptable_seRechazaSinLlegarAlServicio(String descripcion,
            MockHttpServletRequestBuilder peticion, int estado) throws Exception { // CP-REC-43
        mockMvc.perform(peticion).andExpect(status().is(estado));

        verifyNoInteractions(servicio);
    }

    // ------------------------------------------------------------------ CP-REC-44 / 45 / 46

    @Test
    void solicitar_cabeceraXForwardedForDeUnClienteSinProxyDeConfianza_seIgnora() throws Exception { // CP-REC-44
        mockMvc.perform(recuperacion(cuerpo(EMAIL)).header("X-Forwarded-For", "198.51.100.99"))
                .andExpect(status().isOk());

        // El servicio recibe la IP del socket: un cliente no puede falsificar su origen para esquivar limites
        verify(servicio).solicitar(SOLICITUD, CONTEXTO);
    }

    @Test
    void solicitar_noCreaUnaSesionAutenticada() throws Exception { // CP-REC-45
        MvcResult resultado = mockMvc.perform(recuperacion(cuerpo(EMAIL))).andExpect(status().isOk()).andReturn();

        HttpSession sesion = resultado.getRequest().getSession(false);
        assertThat(sesion == null
                || sesion.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) == null)
                .isTrue();
    }

    @Test
    void solicitar_correoConEspaciosYMayusculas_seEntregaTalCualAlServicio() throws Exception { // CP-REC-46
        // La normalizacion es cosa del servicio (como en el login): el controlador no toca el valor
        mockMvc.perform(recuperacion(cuerpo(" Ana.Garcia@EJEMPLO.es "))).andExpect(status().isOk());

        verify(servicio).solicitar(new SolicitudRecuperacionDTO(" Ana.Garcia@EJEMPLO.es "), CONTEXTO);
    }
}
