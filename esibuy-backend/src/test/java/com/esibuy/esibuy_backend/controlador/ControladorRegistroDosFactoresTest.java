package com.esibuy.esibuy_backend.controlador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
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
import java.util.Collections;
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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
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
import com.esibuy.esibuy_backend.excepcion.CodigoError;
import com.esibuy.esibuy_backend.excepcion.DatosRegistroInvalidosException;
import com.esibuy.esibuy_backend.excepcion.ServicioNoDisponibleException;
import com.esibuy.esibuy_backend.modelo.Rol;
import com.esibuy.esibuy_backend.seguridad.ConfiguracionSeguridad;
import com.esibuy.esibuy_backend.seguridad.EstablecedorSesion;
import com.esibuy.esibuy_backend.seguridad.GestorRegistroDosFactores;
import com.esibuy.esibuy_backend.seguridad.PendienteRegistroDosFactores;
import com.esibuy.esibuy_backend.servicio.ContextoPeticion;
import com.esibuy.esibuy_backend.servicio.PreparacionRegistroDosFactores;
import com.esibuy.esibuy_backend.servicio.ResultadoAutenticacion;
import com.esibuy.esibuy_backend.servicio.ServicioRegistroDosFactores;
import com.jayway.jsonpath.JsonPath;

/**
 * Endpoint POST /api/auth/registro/2fa con la seguridad real y el servicio mockeado (HdU 2FA en el registro, fase A).
 * Casos: CP-2FA-414 a CP-2FA-433.
 *
 * Decisiones que fijan estas pruebas (el equipo puede cambiarlas, pero deben cambiar a la vez aqui):
 *  - POST con JSON {"email": "..."}; 200 con {"uri", "secreto"} y nada mas (no repite el correo ni devuelve el texto
 *    cifrado) y sin cache. Es publico (se usa antes de tener cuenta) pero exige token CSRF, porque guarda estado en la
 *    sesion: el secreto pendiente, cifrado y ligado al correo (GestorRegistroDosFactores), que el registro final recuperara.
 *  - Pedirlo de nuevo sustituye al secreto pendiente. Si la peticion falla (correo invalido, error del servicio), el
 *    pendiente que hubiera NO se toca.
 *  - La sesion: una anonima existente se reutiliza (conserva el token CSRF); una autenticada no se invalida; nada concede acceso.
 *  - Errores: 400 con {"errores": {"email": [codigos]}}, y 503 y 500 genericos con correlationId. Ninguna respuesta repite el
 *    secreto en un error.
 *  - Se rechazan campos desconocidos, el correo en la URL, otros tipos de contenido (415), cuerpos desproporcionados (413) y
 *    los metodos que no son POST (un GET anonimo recibe 401 por la regla general de la API). La IP que recibe el servicio
 *    es la del socket.
 *  - El limite de peticiones del registro por IP (FiltroLimitePeticionesRegistro) tambien cuenta esta ruta; se prueba en
 *    RegistroDosFactoresPreparacionIntegracionMongoTest, porque el filtro no forma parte de este corte web.
 *
 * Terminada: ya no lleva la etiqueta pendiente-2fa y entra en la regresion.
 */
@WebMvcTest(ControladorRegistroDosFactores.class)
@Import({ConfiguracionSeguridad.class, ConfiguracionAplicacion.class, ConfiguracionLogin.class})
class ControladorRegistroDosFactoresTest {

    private static final String RUTA = "/api/auth/registro/2fa";
    private static final String EMAIL = "lucia.torres@ejemplo.es";
    private static final String CIFRADO = "1:" + "A".repeat(64);
    private static final String SECRETO = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    private static final String URI = "otpauth://totp/ESIBuy:lucia.torres%40ejemplo.es?secret=" + SECRETO + "&issuer=ESIBuy";
    private static final String IP_CLIENTE = "203.0.113.7";
    private static final String USER_AGENT = "JUnit-UA";
    private static final ContextoPeticion CONTEXTO = new ContextoPeticion(IP_CLIENTE, USER_AGENT);
    private static final String CUERPO = "{\"email\":\"" + EMAIL + "\"}";
    private static final PreparacionRegistroDosFactores PREPARACION =
            new PreparacionRegistroDosFactores(EMAIL, CIFRADO, URI, SECRETO);

    @Autowired
    private WebApplicationContext contexto;
    @Autowired
    private GestorRegistroDosFactores gestor;
    @MockitoBean
    private ServicioRegistroDosFactores servicio;

    private MockMvc mockMvc;

    @BeforeEach
    void prepararMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(contexto)
                .addFilters(new FiltroCorrelacionId())
                .apply(springSecurity())
                .build();
    }

    // ------------------------------------------------------------------ utilidades

    private static RequestPostProcessor desdeLaIp(String ip) {
        return peticion -> {
            peticion.setRemoteAddr(ip);
            return peticion;
        };
    }

    private static MockHttpServletRequestBuilder preparar(MockHttpSession sesion, String cuerpo) {
        MockHttpServletRequestBuilder peticion = post(RUTA).with(csrf()).with(desdeLaIp(IP_CLIENTE))
                .header(HttpHeaders.USER_AGENT, USER_AGENT).contentType(MediaType.APPLICATION_JSON).content(cuerpo);
        return sesion == null ? peticion : peticion.session(sesion);
    }

    private static String cuerpoDe(MvcResult resultado) throws Exception {
        return resultado.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private java.util.Optional<PendienteRegistroDosFactores> pendienteDe(MockHttpSession sesion) {
        MockHttpServletRequest peticion = new MockHttpServletRequest();
        peticion.setSession(sesion);
        return gestor.vigente(peticion);
    }

    // ------------------------------------------------------------------ acierto

    @Test
    void preparar_devuelve200ConLaUriYElSecretoYNadaMasSinCache() throws Exception { // CP-2FA-414
        when(servicio.preparar(EMAIL, CONTEXTO)).thenReturn(PREPARACION);

        MvcResult resultado = mockMvc.perform(preparar(null, CUERPO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uri").value(URI))
                .andExpect(jsonPath("$.secreto").value(SECRETO))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
                .andReturn();

        Map<String, Object> campos = JsonPath.read(cuerpoDe(resultado), "$");
        assertThat(campos).containsOnlyKeys("uri", "secreto");
        assertThat(cuerpoDe(resultado)).doesNotContain(CIFRADO);
        verify(servicio).preparar(EMAIL, CONTEXTO);
    }

    @Test
    void preparar_guardaElSecretoCifradoYElCorreoComoPendienteDeLaSesion() throws Exception { // CP-2FA-415
        when(servicio.preparar(any(), any())).thenReturn(PREPARACION);

        MvcResult resultado = mockMvc.perform(preparar(null, CUERPO)).andReturn();

        MockHttpSession sesion = (MockHttpSession) resultado.getRequest().getSession(false);
        PendienteRegistroDosFactores pendiente = pendienteDe(sesion).orElseThrow();
        assertThat(pendiente.email()).isEqualTo(EMAIL);
        assertThat(pendiente.secretoCifrado()).isEqualTo(CIFRADO);
        assertThat(pendiente.intentosFallidos()).isZero();
    }

    @Test
    void preparar_noConcedeNada_laSesionNoQuedaAutenticada() throws Exception { // CP-2FA-416
        when(servicio.preparar(any(), any())).thenReturn(PREPARACION);

        MvcResult resultado = mockMvc.perform(preparar(null, CUERPO)).andReturn();

        MockHttpSession sesion = (MockHttpSession) resultado.getRequest().getSession(false);
        assertThat(sesion.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)).isNull();
        assertThat(sesion.getAttribute(EstablecedorSesion.ATRIBUTO_USUARIO)).isNull();
        mockMvc.perform(get("/api/auth/me").session(sesion)).andExpect(status().isUnauthorized());
    }

    @Test
    void preparar_reutilizaLaSesionAnonimaQueGuardaElTokenCsrf() throws Exception { // CP-2FA-417
        when(servicio.preparar(any(), any())).thenReturn(PREPARACION);
        MockHttpSession anonima = new MockHttpSession();
        anonima.setAttribute("token-csrf", "valor");

        MvcResult resultado = mockMvc.perform(preparar(anonima, CUERPO)).andReturn();

        assertThat(resultado.getRequest().getSession(false)).isSameAs(anonima);
        assertThat(anonima.getAttribute("token-csrf")).isEqualTo("valor");
        assertThat(pendienteDe(anonima)).isPresent();
    }

    @Test
    void preparar_conUnaSesionAutenticada_noLaInvalida() throws Exception { // CP-2FA-418
        when(servicio.preparar(any(), any())).thenReturn(PREPARACION);
        ResultadoAutenticacion usuario = new ResultadoAutenticacion("1", "otro@ejemplo.es", "Otro", Rol.CLIENTE);
        MockHttpSession autenticada = new MockHttpSession();
        autenticada.setAttribute(EstablecedorSesion.ATRIBUTO_USUARIO, usuario);

        mockMvc.perform(preparar(autenticada, CUERPO)).andExpect(status().isOk());

        assertThat(autenticada.isInvalid()).isFalse();
        assertThat(autenticada.getAttribute(EstablecedorSesion.ATRIBUTO_USUARIO)).isEqualTo(usuario);
        assertThat(pendienteDe(autenticada)).isPresent();
    }

    @Test
    void preparar_denuevo_sustituyeAlSecretoPendiente() throws Exception { // CP-2FA-419
        String otroCifrado = "1:" + "B".repeat(64);
        when(servicio.preparar(any(), any())).thenReturn(PREPARACION)
                .thenReturn(new PreparacionRegistroDosFactores("otro@ejemplo.es", otroCifrado, URI, SECRETO));
        MockHttpSession sesion = new MockHttpSession();

        mockMvc.perform(preparar(sesion, CUERPO)).andExpect(status().isOk());
        mockMvc.perform(preparar(sesion, "{\"email\":\"otro@ejemplo.es\"}")).andExpect(status().isOk());

        PendienteRegistroDosFactores pendiente = pendienteDe(sesion).orElseThrow();
        assertThat(pendiente.email()).isEqualTo("otro@ejemplo.es");
        assertThat(pendiente.secretoCifrado()).isEqualTo(otroCifrado);
    }

    @Test
    void preparar_laIpEsLaDelSocket_yElXForwardedForSeIgnora() throws Exception { // CP-2FA-420
        when(servicio.preparar(any(), any())).thenReturn(PREPARACION);

        mockMvc.perform(preparar(null, CUERPO).header("X-Forwarded-For", "198.51.100.99")).andExpect(status().isOk());

        verify(servicio).preparar(EMAIL, CONTEXTO);
    }

    @Test
    void preparar_elCorreoSeEntregaAlServicioTalCual_lanormalizacionEsDelServicio() throws Exception { // CP-2FA-421
        when(servicio.preparar(any(), any())).thenReturn(PREPARACION);

        mockMvc.perform(preparar(null, "{\"email\":\"  LUCIA.Torres@Ejemplo.ES \"}")).andExpect(status().isOk());

        verify(servicio).preparar("  LUCIA.Torres@Ejemplo.ES ", CONTEXTO);
    }

    // ------------------------------------------------------------------ CSRF

    @Test
    void preparar_exigeTokenCsrf() throws Exception { // CP-2FA-422
        MockHttpSession sesion = new MockHttpSession();

        mockMvc.perform(post(RUTA).session(sesion).with(desdeLaIp(IP_CLIENTE)).contentType(MediaType.APPLICATION_JSON)
                .content(CUERPO)).andExpect(status().isForbidden());
        mockMvc.perform(preparar(sesion, CUERPO).with(csrf().useInvalidToken())).andExpect(status().isForbidden());

        verifyNoInteractions(servicio);
        assertThat(pendienteDe(sesion)).isEmpty();
    }

    // ------------------------------------------------------------------ errores

    @Test
    void preparar_conUnCorreoInvalido_devuelve400ConElCampo_yNoToca_elPendienteAnterior() throws Exception { // CP-2FA-423
        when(servicio.preparar(EMAIL, CONTEXTO)).thenReturn(PREPARACION);
        when(servicio.preparar("no-es-un-correo", CONTEXTO)).thenThrow(new DatosRegistroInvalidosException(
                Map.of("email", Set.of(CodigoError.FORMATO_INVALIDO))));
        MockHttpSession sesion = new MockHttpSession();
        mockMvc.perform(preparar(sesion, CUERPO)).andExpect(status().isOk());

        MvcResult resultado = mockMvc.perform(preparar(sesion, "{\"email\":\"no-es-un-correo\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores.email[0]").value("FORMATO_INVALIDO"))
                .andReturn();

        assertThat(cuerpoDe(resultado)).doesNotContain(SECRETO, CIFRADO);
        // El secreto que se pidio antes sigue valiendo
        assertThat(pendienteDe(sesion).orElseThrow().secretoCifrado()).isEqualTo(CIFRADO);
    }

    @Test
    void preparar_siElServicioFalla_devuelve503OEl500GenericosConCorrelationIdYSinPendiente() throws Exception { // CP-2FA-424
        MockHttpSession sesion = new MockHttpSession();
        when(servicio.preparar(any(), any())).thenThrow(new ServicioNoDisponibleException(null));

        MvcResult noDisponible = mockMvc.perform(preparar(sesion, CUERPO))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andReturn();

        // doThrow y no when(...): el servicio ya esta configurado para lanzar y when() lo invocaria
        org.mockito.Mockito.doThrow(new IllegalStateException("detalle interno")).when(servicio).preparar(any(), any());
        MvcResult inesperado = mockMvc.perform(preparar(sesion, CUERPO))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andReturn();

        assertThat(cuerpoDe(noDisponible) + cuerpoDe(inesperado)).doesNotContain("detalle interno");
        assertThat(pendienteDe(sesion)).isEmpty();
    }

    // ------------------------------------------------------------------ forma de la peticion

    private static Stream<Arguments> cuerposRechazados() {
        return Stream.of(
                arguments("campo desconocido", "{\"email\":\"" + EMAIL + "\",\"contrasena\":\"x\"}"),
                arguments("el tipo de cuenta en el cuerpo", "{\"email\":\"" + EMAIL + "\",\"tipoCuenta\":\"ADMIN\"}"),
                arguments("correo que es un objeto", "{\"email\":{\"a\":1}}"),
                arguments("correo que es una lista", "{\"email\":[\"a@b.es\"]}"),
                arguments("JSON roto", "{\"email\":"),
                arguments("sin cuerpo", ""));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cuerposRechazados")
    void preparar_cuerposQueNoSonValidos_devuelven400YNoLlamanAlServicio(String descripcion, String cuerpo)
            throws Exception { // CP-2FA-425
        mockMvc.perform(preparar(null, cuerpo)).andExpect(status().isBadRequest());

        verifyNoInteractions(servicio);
    }

    @Test
    void preparar_elCorreoEnLaUrl_seRechazaAunqueElCuerpoSeaValido() throws Exception { // CP-2FA-426
        mockMvc.perform(preparar(null, CUERPO).param("email", EMAIL)).andExpect(status().isBadRequest());

        verifyNoInteractions(servicio);
    }

    @Test
    void preparar_otrosTiposDeContenido_devuelven415() throws Exception { // CP-2FA-427
        mockMvc.perform(post(RUTA).with(csrf()).contentType(MediaType.TEXT_PLAIN).content(CUERPO))
                .andExpect(status().isUnsupportedMediaType());
        mockMvc.perform(post(RUTA).with(csrf()).contentType(MediaType.APPLICATION_FORM_URLENCODED).content("email=" + EMAIL))
                .andExpect(status().isUnsupportedMediaType());

        verifyNoInteractions(servicio);
    }

    @Test
    void preparar_cuerpoDesproporcionado_devuelve413() throws Exception { // CP-2FA-428
        mockMvc.perform(preparar(null, "{\"email\":\"" + "a".repeat(20_000) + "\"}")).andExpect(status().is(413));

        verifyNoInteractions(servicio);
    }

    @Test
    void preparar_elMetodoGetNoExiste_yDaLa401DeCualquierGetAnonimoDeLaApi() throws Exception { // CP-2FA-429
        mockMvc.perform(get(RUTA)).andExpect(status().isUnauthorized());

        verifyNoInteractions(servicio);
    }

    // ------------------------------------------------------------------ nada sensible

    @Test
    void preparar_noDejaElSecretoEnClaroEnLaSesion_soloElTextoCifrado() throws Exception { // CP-2FA-430
        when(servicio.preparar(any(), any())).thenReturn(PREPARACION);

        MvcResult resultado = mockMvc.perform(preparar(null, CUERPO)).andReturn();

        MockHttpSession sesion = (MockHttpSession) resultado.getRequest().getSession(false);
        Object guardado = sesion.getAttribute(GestorRegistroDosFactores.ATRIBUTO_PENDIENTE);
        assertThat(Collections.list(sesion.getAttributeNames())).containsExactly(GestorRegistroDosFactores.ATRIBUTO_PENDIENTE);
        assertThat(String.valueOf(guardado)).doesNotContain(SECRETO, CIFRADO);
        assertThat(((PendienteRegistroDosFactores) guardado).secretoCifrado()).isEqualTo(CIFRADO);
    }

    @Test
    void preparar_conUnaSesionNuevaPorPeticion_cadaUnaTieneSuPropioPendiente() throws Exception { // CP-2FA-431
        when(servicio.preparar(any(), any())).thenReturn(PREPARACION);

        MvcResult uno = mockMvc.perform(preparar(null, CUERPO)).andReturn();
        MvcResult otro = mockMvc.perform(preparar(null, CUERPO)).andReturn();

        MockHttpSession sesionUno = (MockHttpSession) uno.getRequest().getSession(false);
        MockHttpSession sesionOtro = (MockHttpSession) otro.getRequest().getSession(false);
        assertThat(sesionUno).isNotSameAs(sesionOtro);
        assertThat(pendienteDe(sesionUno)).isPresent();
        assertThat(pendienteDe(sesionOtro)).isPresent();
    }

    @Test
    void preparar_unCorreoVacioONuloLlegaAlServicio_queEsQuienLoValida() throws Exception { // CP-2FA-432
        when(servicio.preparar(any(), any())).thenReturn(PREPARACION);

        mockMvc.perform(preparar(null, "{}")).andExpect(status().isOk());
        mockMvc.perform(preparar(null, "{\"email\":null}")).andExpect(status().isOk());
        mockMvc.perform(preparar(null, "{\"email\":\"\"}")).andExpect(status().isOk());

        verify(servicio, org.mockito.Mockito.times(2)).preparar(null, CONTEXTO);
        verify(servicio).preparar("", CONTEXTO);
    }

    @Test
    void preparar_noExigeSesionNiRol() throws Exception { // CP-2FA-433
        when(servicio.preparar(any(), any())).thenReturn(PREPARACION);

        // Anonimo, sin cookies: es lo que usa quien se esta registrando
        mockMvc.perform(preparar(null, CUERPO)).andExpect(status().isOk());
    }
}
