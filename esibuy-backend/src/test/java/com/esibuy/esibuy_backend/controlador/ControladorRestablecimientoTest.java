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
import com.esibuy.esibuy_backend.dto.RespuestaRestablecimientoDTO;
import com.esibuy.esibuy_backend.dto.SolicitudRestablecimientoDTO;
import com.esibuy.esibuy_backend.excepcion.CodigoError;
import com.esibuy.esibuy_backend.excepcion.DatosRestablecimientoInvalidosException;
import com.esibuy.esibuy_backend.excepcion.DemasiadosIntentosRestablecimientoException;
import com.esibuy.esibuy_backend.excepcion.ServicioNoDisponibleException;
import com.esibuy.esibuy_backend.seguridad.ConfiguracionSeguridad;
import com.esibuy.esibuy_backend.servicio.ContextoPeticion;
import com.esibuy.esibuy_backend.servicio.ServicioRestablecimiento;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.HttpSession;

/**
 * Endpoint POST /api/auth/recuperacion/restablecer con la seguridad real y el servicio mockeado (HdU recuperacion de
 * contrasena, fase 4). Casos: CP-RST-60 a CP-RST-66.
 *
 * Decisiones que fijan estas pruebas (el equipo puede cambiarlas, pero deben cambiar a la vez aqui):
 *  - POST con JSON {"token": "...", "contrasena": "..."}. 200 con {"mensaje": "..."} y nada mas: no inicia sesion, el
 *    usuario entra despues con su contrasena nueva.
 *  - Publico pero con token CSRF, como el login.
 *  - 400 con {"errores": {campo: [codigos]}} (campo token: ENLACE_INVALIDO; campo contrasena: los codigos de la
 *    politica), 429 sin detalles cuando la IP agota sus intentos, 503 y 500 genericos con correlationId. Ninguna
 *    respuesta repite el token ni la contrasena.
 *  - Se rechazan campos desconocidos, el token o la contrasena en la URL (acabarian en logs), otros tipos de contenido
 *    (415), cuerpos desproporcionados (413) y cualquier metodo distinto de POST (un GET anonimo recibe 401 por la regla
 *    general de la API).
 *  - La IP que recibe el servicio es la del socket; X-Forwarded-For solo se respeta desde proxies de confianza.
 *  - La contrasena se entrega al servicio tal cual, con sus espacios.
 *
 * Terminada: ya no lleva la etiqueta pendiente-recuperacion y entra en la regresion.
 */
@WebMvcTest(ControladorRestablecimiento.class)
@Import({ConfiguracionSeguridad.class, ConfiguracionAplicacion.class, ConfiguracionLogin.class})
class ControladorRestablecimientoTest {

    private static final String RUTA = "/api/auth/recuperacion/restablecer";
    private static final String TOKEN = "Zk3-Qw9_Lm2xPa7RtYb4NcV8HdS1JfGe6UoIi0KuXyA";
    private static final String CONTRASENA = "Nueva-Clave-De-Prueba-42!";
    private static final String IP_CLIENTE = "203.0.113.7";
    private static final String USER_AGENT = "JUnit-UA";
    private static final SolicitudRestablecimientoDTO SOLICITUD = new SolicitudRestablecimientoDTO(TOKEN, CONTRASENA);
    private static final ContextoPeticion CONTEXTO = new ContextoPeticion(IP_CLIENTE, USER_AGENT);

    @Autowired
    private WebApplicationContext contexto;
    @MockitoBean
    private ServicioRestablecimiento servicio;

    private MockMvc mockMvc;

    @BeforeEach
    void prepararMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(contexto)
                .addFilters(new FiltroCorrelacionId())
                .apply(springSecurity())
                .build();
    }

    // ------------------------------------------------------------------ utilidades

    private static String cuerpo(String token, String contrasena) {
        return "{\"token\":\"" + token + "\",\"contrasena\":\"" + contrasena + "\"}";
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

    private static MockHttpServletRequestBuilder restablecimiento(String contenido) {
        return postSinCsrf(RUTA, MediaType.APPLICATION_JSON, contenido).with(csrf());
    }

    private static String cuerpoDe(MvcResult resultado) throws Exception {
        return resultado.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ CP-RST-60

    @Test
    void restablecer_peticionValida_devuelve200SinDatosYPasaAlServicioLaSolicitudYElOrigen() throws Exception {
        // CP-RST-60
        MvcResult resultado = mockMvc.perform(restablecimiento(cuerpo(TOKEN, CONTRASENA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mensaje").value(RespuestaRestablecimientoDTO.MENSAJE_CORRECTO))
                .andReturn();

        // Exactamente un campo, sin eco del token ni de la contrasena
        Map<String, Object> campos = JsonPath.read(cuerpoDe(resultado), "$");
        assertThat(campos.keySet()).containsExactly("mensaje");
        assertThat(cuerpoDe(resultado)).doesNotContain(TOKEN, CONTRASENA);
        verify(servicio).restablecer(SOLICITUD, CONTEXTO);
    }

    @Test
    void restablecer_contrasenaConEspaciosYCaracteresEspeciales_seEntregaTalCualAlServicio() throws Exception {
        // CP-RST-60
        String rara = "  \\\"Una\\\" clave \\\\ con ñ y 😀  ";

        mockMvc.perform(restablecimiento(cuerpo(TOKEN, rara))).andExpect(status().isOk());

        verify(servicio).restablecer(new SolicitudRestablecimientoDTO(TOKEN, "  \"Una\" clave \\ con ñ y 😀  "), CONTEXTO);
    }

    // ------------------------------------------------------------------ CP-RST-61

    @Test
    void restablecer_exigeTokenCsrf() throws Exception { // CP-RST-61
        // Con token valido, un visitante anonimo llega al servicio (endpoint publico)
        mockMvc.perform(restablecimiento(cuerpo(TOKEN, CONTRASENA))).andExpect(status().isOk());
        verify(servicio, times(1)).restablecer(any(), any());

        // Sin token, 403; con un token invalido, tambien. El servicio no se invoca
        mockMvc.perform(postSinCsrf(RUTA, MediaType.APPLICATION_JSON, cuerpo(TOKEN, CONTRASENA)))
                .andExpect(status().isForbidden());
        mockMvc.perform(postSinCsrf(RUTA, MediaType.APPLICATION_JSON, cuerpo(TOKEN, CONTRASENA))
                .with(csrf().useInvalidToken())).andExpect(status().isForbidden());
        verify(servicio, times(1)).restablecer(any(), any());
    }

    // ------------------------------------------------------------------ CP-RST-62

    @Test
    void restablecer_enlaceNoValido_devuelve400ConElErrorDelCampoToken() throws Exception { // CP-RST-62
        doThrow(new DatosRestablecimientoInvalidosException(Map.of("token", Set.of(CodigoError.ENLACE_INVALIDO))))
                .when(servicio).restablecer(any(), any());

        MvcResult resultado = mockMvc.perform(restablecimiento(cuerpo(TOKEN, CONTRASENA)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores.token[0]").value("ENLACE_INVALIDO"))
                .andReturn();

        assertThat(cuerpoDe(resultado)).doesNotContain(TOKEN, CONTRASENA);
    }

    @Test
    void restablecer_contrasenaQueIncumpleLaPolitica_devuelve400ConTodosLosCodigos() throws Exception { // CP-RST-62
        doThrow(new DatosRestablecimientoInvalidosException(Map.of("contrasena",
                Set.of(CodigoError.CONTRASENA_CORTA, CodigoError.CONTRASENA_SIN_NUMERO))))
                .when(servicio).restablecer(any(), any());

        MvcResult resultado = mockMvc.perform(restablecimiento(cuerpo(TOKEN, "corta")))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertThat(cuerpoDe(resultado)).contains("\"errores\"", "CONTRASENA_CORTA", "CONTRASENA_SIN_NUMERO")
                .doesNotContain(TOKEN, "corta\"");
    }

    @Test
    void restablecer_demasiadosIntentos_devuelve429SinDetalles() throws Exception { // CP-RST-62
        doThrow(new DemasiadosIntentosRestablecimientoException()).when(servicio).restablecer(any(), any());

        MvcResult resultado = mockMvc.perform(restablecimiento(cuerpo(TOKEN, CONTRASENA)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.mensaje").isNotEmpty())
                .andReturn();

        assertThat(cuerpoDe(resultado)).doesNotContain(TOKEN, CONTRASENA, IP_CLIENTE);
    }

    @Test
    void restablecer_servicioNoDisponible_devuelve503ConCorrelationId() throws Exception { // CP-RST-62
        doThrow(new ServicioNoDisponibleException(null)).when(servicio).restablecer(any(), any());

        mockMvc.perform(restablecimiento(cuerpo(TOKEN, CONTRASENA)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.mensaje").isNotEmpty())
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void restablecer_errorInesperado_devuelve500GenericoSinDetalles() throws Exception { // CP-RST-62
        doThrow(new IllegalStateException("fallo interno en usuarios")).when(servicio).restablecer(any(), any());

        MvcResult resultado = mockMvc.perform(restablecimiento(cuerpo(TOKEN, CONTRASENA)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andReturn();

        assertThat(cuerpoDe(resultado)).doesNotContain("usuarios", TOKEN, CONTRASENA);
    }

    // ------------------------------------------------------------------ CP-RST-63

    private static MockHttpServletRequestBuilder enJson(String contenido) {
        return restablecimiento(contenido);
    }

    private static MockHttpServletRequestBuilder jsonCon(String campoExtra) {
        return restablecimiento("{\"token\":\"" + TOKEN + "\",\"contrasena\":\"" + CONTRASENA + "\"," + campoExtra + "}");
    }

    static Stream<Arguments> peticionesNoAceptables() {
        return Stream.of(
                arguments("campo extra: email", jsonCon("\"email\":\"ana@ejemplo.es\""), 400),
                arguments("campo extra: rol", jsonCon("\"rol\":\"ADMIN\""), 400),
                arguments("campo extra: estado", jsonCon("\"estado\":\"ACTIVO\""), 400),
                arguments("campo extra: idUsuario", jsonCon("\"idUsuario\":\"665f1c2e9b1e8a3d4c5b6a79\""), 400),
                arguments("campo $where", jsonCon("\"$where\":\"sleep(5000)\""), 400),
                arguments("clave con punto", jsonCon("\"a.b\":\"x\""), 400),
                arguments("token como objeto con $ne",
                        enJson("{\"token\":{\"$ne\":null},\"contrasena\":\"" + CONTRASENA + "\"}"), 400),
                arguments("contrasena como objeto con $gt",
                        enJson("{\"token\":\"" + TOKEN + "\",\"contrasena\":{\"$gt\":\"\"}}"), 400),
                arguments("contrasena como lista",
                        enJson("{\"token\":\"" + TOKEN + "\",\"contrasena\":[\"a\",\"b\"]}"), 400),
                arguments("sin cuerpo", enJson(""), 400),
                arguments("JSON mal formado", enJson("{\"token\":\"abc"), 400),
                arguments("Content-Type text/plain",
                        postSinCsrf(RUTA, MediaType.TEXT_PLAIN, cuerpo(TOKEN, CONTRASENA)).with(csrf()), 415),
                arguments("Content-Type de formulario", postSinCsrf(RUTA, MediaType.APPLICATION_FORM_URLENCODED,
                        "token=" + TOKEN + "&contrasena=" + CONTRASENA).with(csrf()), 415),
                arguments("cuerpo desproporcionado", enJson(cuerpo(TOKEN, "a".repeat(20_000))), 413),
                arguments("token en la URL", postSinCsrf(RUTA + "?token=" + TOKEN, MediaType.APPLICATION_JSON,
                        cuerpo(TOKEN, CONTRASENA)).with(csrf()), 400),
                arguments("contrasena en la URL", postSinCsrf(RUTA + "?contrasena=" + CONTRASENA,
                        MediaType.APPLICATION_JSON, cuerpo(TOKEN, CONTRASENA)).with(csrf()), 400),
                arguments("peticion GET (anonima: 401 por la regla general)",
                        get(RUTA).with(desdeLaIp(IP_CLIENTE)), 401));
    }

    @ParameterizedTest(name = "{0} -> {2}")
    @MethodSource("peticionesNoAceptables")
    void restablecer_peticionNoAceptable_seRechazaSinLlegarAlServicio(String descripcion,
            MockHttpServletRequestBuilder peticion, int estado) throws Exception { // CP-RST-63
        mockMvc.perform(peticion).andExpect(status().is(estado));

        verifyNoInteractions(servicio);
    }

    // ------------------------------------------------------------------ CP-RST-64 / 65 / 66

    @Test
    void restablecer_noIniciaSesion() throws Exception { // CP-RST-64
        MvcResult resultado = mockMvc.perform(restablecimiento(cuerpo(TOKEN, CONTRASENA)))
                .andExpect(status().isOk()).andReturn();

        // El usuario entra despues con su contrasena nueva: restablecer no crea una sesion autenticada
        HttpSession sesion = resultado.getRequest().getSession(false);
        assertThat(sesion == null
                || sesion.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) == null)
                .isTrue();
    }

    @Test
    void restablecer_cabeceraXForwardedForDeUnClienteSinProxyDeConfianza_seIgnora() throws Exception { // CP-RST-65
        mockMvc.perform(restablecimiento(cuerpo(TOKEN, CONTRASENA)).header("X-Forwarded-For", "198.51.100.99"))
                .andExpect(status().isOk());

        // Si se creyera la cabecera, un cliente podria esquivar el limite de intentos cambiando de "IP" en cada peticion
        verify(servicio).restablecer(SOLICITUD, CONTEXTO);
    }

    @Test
    void solicitudDto_toString_noIncluyeElTokenNiLaContrasena() { // CP-RST-66
        assertThat(SOLICITUD.toString()).doesNotContain(TOKEN, CONTRASENA);
    }
}
