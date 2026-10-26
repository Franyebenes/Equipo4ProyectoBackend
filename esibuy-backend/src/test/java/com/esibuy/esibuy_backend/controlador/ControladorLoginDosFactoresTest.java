package com.esibuy.esibuy_backend.controlador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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
import java.time.Duration;
import java.time.Instant;
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
import com.esibuy.esibuy_backend.dto.SolicitudSegundoFactorLoginDTO;
import com.esibuy.esibuy_backend.excepcion.CodigoError;
import com.esibuy.esibuy_backend.excepcion.CredencialesInvalidasException;
import com.esibuy.esibuy_backend.excepcion.DatosDosFactoresInvalidosException;
import com.esibuy.esibuy_backend.excepcion.DemasiadosIntentosDosFactoresException;
import com.esibuy.esibuy_backend.excepcion.SegundoFactorNoVigenteException;
import com.esibuy.esibuy_backend.excepcion.ServicioNoDisponibleException;
import com.esibuy.esibuy_backend.modelo.Rol;
import com.esibuy.esibuy_backend.seguridad.ConfiguracionSeguridad;
import com.esibuy.esibuy_backend.seguridad.DesafioSegundoFactor;
import com.esibuy.esibuy_backend.seguridad.EstablecedorSesion;
import com.esibuy.esibuy_backend.seguridad.GestorDesafioSegundoFactor;
import com.esibuy.esibuy_backend.servicio.AuditoriaSeguridad;
import com.esibuy.esibuy_backend.servicio.ContextoPeticion;
import com.esibuy.esibuy_backend.servicio.ResultadoAutenticacion;
import com.esibuy.esibuy_backend.servicio.ServicioAutenticacion;
import com.esibuy.esibuy_backend.servicio.ServicioDosFactores;
import com.jayway.jsonpath.JsonPath;

/**
 * Login en dos pasos con la seguridad real y los servicios mockeados (HdU 2FA, fase 3). Casos: CP-2FA-334 a CP-2FA-362.
 *
 * Paso 1, POST /api/auth/login (sin cambios para las cuentas sin 2FA): si la cuenta tiene el 2FA activo responde 200 con
 * {"requiereSegundoFactor": true, "mensaje": ...} y NADA mas (ni id, ni correo, ni rol), y no concede sesion: guarda un desafio
 * pendiente en la sesion, que sigue siendo anonima (401 en /api/auth/me y en todo lo protegido).
 *
 * Paso 2, POST /api/auth/2fa/verificar con {"codigo": "..."}: publico pero con token CSRF, como el login. Con un desafio vigente
 * y un codigo correcto responde 200 con la misma respuesta que un login normal y crea la sesion definitiva (la anterior se
 * invalida: nuevo identificador, sin fijacion de sesion) sin el desafio. Errores:
 *  - 401 sin desafio vigente (no lo hay, caduco, ya se uso, o la cuenta cambio entre los dos pasos): siempre el mismo
 *    mensaje, y el desafio se descarta;
 *  - 400 con {"errores": {"codigo": [...]}} si el codigo no vale. Cada fallo cuenta contra el desafio: al quinto se descarta
 *    (esa respuesta sigue siendo la del codigo incorrecto, y la siguiente es un 401). Un codigo vacio o un 429 no cuentan;
 *  - 429 si la cuenta o la IP agotaron sus fallos (limite del segundo factor, independiente del bloqueo del login);
 *  - 503 y 500 genericos con correlationId. Ninguna respuesta repite el codigo.
 * Se rechazan campos desconocidos, el codigo en la URL, otros tipos de contenido (415) y cuerpos desproporcionados (413). La IP
 * que recibe el servicio es la del socket.
 *
 * Terminada: ya no lleva la etiqueta pendiente-2fa y entra en la regresion.
 */
@WebMvcTest({ControladorLogin.class, ControladorSegundoFactorLogin.class, ControladorSesion.class})
@Import({ConfiguracionSeguridad.class, ConfiguracionAplicacion.class, ConfiguracionLogin.class})
class ControladorLoginDosFactoresTest {

    private static final String LOGIN = "/api/auth/login";
    private static final String VERIFICAR = "/api/auth/2fa/verificar";
    private static final String IP_CLIENTE = "203.0.113.7";
    private static final String USER_AGENT = "JUnit-UA";
    private static final ContextoPeticion CONTEXTO = new ContextoPeticion(IP_CLIENTE, USER_AGENT);
    private static final String ID = "665f1c2e9b1e8a3d4c5b6a79";
    private static final String EMAIL = "ana.garcia@ejemplo.es";
    private static final String CODIGO = "123456";
    private static final ResultadoAutenticacion CON_2FA =
            new ResultadoAutenticacion(ID, EMAIL, "Ana", Rol.CLIENTE, "avatar-01", true);
    private static final ResultadoAutenticacion SIN_2FA =
            new ResultadoAutenticacion(ID, EMAIL, "Ana", Rol.CLIENTE, "avatar-01");
    private static final String CUERPO_LOGIN = "{\"email\":\"" + EMAIL + "\",\"contrasena\":\"Clave-De-Prueba-42!\"}";
    private static final String CUERPO_CODIGO = "{\"codigo\":\"" + CODIGO + "\"}";

    @Autowired
    private WebApplicationContext contexto;
    @Autowired
    private EstablecedorSesion establecedorSesion;
    @Autowired
    private GestorDesafioSegundoFactor gestor;
    @MockitoBean
    private ServicioAutenticacion servicioAutenticacion;
    @MockitoBean
    private ServicioDosFactores servicioDosFactores;
    @MockitoBean
    private AuditoriaSeguridad auditoria;

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

    private static MockHttpServletRequestBuilder postJson(String ruta, MockHttpSession sesion, String cuerpo) {
        MockHttpServletRequestBuilder peticion = post(ruta).with(csrf()).with(desdeLaIp(IP_CLIENTE))
                .header(HttpHeaders.USER_AGENT, USER_AGENT).contentType(MediaType.APPLICATION_JSON).content(cuerpo);
        return sesion == null ? peticion : peticion.session(sesion);
    }

    private static String cuerpoDe(MvcResult resultado) throws Exception {
        return resultado.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** Una sesion con el desafio pendiente de CON_2FA, tal y como la deja el primer paso. */
    private MockHttpSession sesionConDesafio() {
        MockHttpServletRequest peticion = new MockHttpServletRequest();
        gestor.iniciar(peticion, CON_2FA);
        return (MockHttpSession) peticion.getSession(false);
    }

    private MockHttpSession sesionAutenticada() {
        MockHttpServletRequest peticion = new MockHttpServletRequest();
        establecedorSesion.establecer(peticion, SIN_2FA);
        return (MockHttpSession) peticion.getSession(false);
    }

    private java.util.Optional<DesafioSegundoFactor> desafioDe(MockHttpSession sesion) {
        MockHttpServletRequest peticion = new MockHttpServletRequest();
        peticion.setSession(sesion);
        return gestor.vigente(peticion);
    }

    private int estado(MockHttpSession sesion, String ruta) throws Exception {
        return mockMvc.perform(get(ruta).session(sesion)).andReturn().getResponse().getStatus();
    }

    // ------------------------------------------------------------------ paso 1: login

    @Test
    void login_conDosFactores_responde200ConElIndicadorYNadaMas() throws Exception { // CP-2FA-334
        when(servicioAutenticacion.autenticar(any(), any())).thenReturn(CON_2FA);

        MvcResult resultado = mockMvc.perform(postJson(LOGIN, null, CUERPO_LOGIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requiereSegundoFactor").value(true))
                .andExpect(jsonPath("$.mensaje").isNotEmpty())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
                .andReturn();

        // Ni id, ni correo, ni rol, ni avatar: todavia no ha entrado
        Map<String, Object> campos = JsonPath.read(cuerpoDe(resultado), "$");
        assertThat(campos).containsOnlyKeys("requiereSegundoFactor", "mensaje");
        assertThat(cuerpoDe(resultado)).doesNotContain(EMAIL, ID);
    }

    @Test
    void login_conDosFactores_noConcedeSesion_soloGuardaElDesafio() throws Exception { // CP-2FA-335
        when(servicioAutenticacion.autenticar(any(), any())).thenReturn(CON_2FA);

        MvcResult resultado = mockMvc.perform(postJson(LOGIN, null, CUERPO_LOGIN)).andReturn();

        MockHttpSession sesion = (MockHttpSession) resultado.getRequest().getSession(false);
        assertThat(sesion.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)).isNull();
        assertThat(sesion.getAttribute(EstablecedorSesion.ATRIBUTO_USUARIO)).isNull();
        DesafioSegundoFactor desafio = desafioDe(sesion).orElseThrow();
        assertThat(desafio.usuario().id()).isEqualTo(ID);
        assertThat(desafio.intentosFallidos()).isZero();
    }

    @Test
    void conElDesafioPendiente_laSesionSigueSiendoAnonima() throws Exception { // CP-2FA-336
        MockHttpSession pendiente = sesionConDesafio();

        assertThat(estado(pendiente, "/api/auth/me")).isEqualTo(401);
        assertThat(estado(pendiente, "/api/2fa")).isEqualTo(401);
        assertThat(estado(pendiente, "/api/carrito")).isEqualTo(401);
        assertThat(estado(pendiente, "/api/admin/usuarios")).isEqualTo(401);
    }

    @Test
    void login_sinDosFactores_seComportaComoSiempre() throws Exception { // CP-2FA-337
        when(servicioAutenticacion.autenticar(any(), any())).thenReturn(SIN_2FA);

        MvcResult resultado = mockMvc.perform(postJson(LOGIN, null, CUERPO_LOGIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ID))
                .andExpect(jsonPath("$.email").value(EMAIL))
                .andExpect(jsonPath("$.rol").value("CLIENTE"))
                .andReturn();

        Map<String, Object> campos = JsonPath.read(cuerpoDe(resultado), "$");
        assertThat(campos).containsOnlyKeys("id", "email", "nombre", "rol", "avatar", "mensaje");
        MockHttpSession sesion = (MockHttpSession) resultado.getRequest().getSession(false);
        assertThat(sesion.getAttribute(EstablecedorSesion.ATRIBUTO_USUARIO)).isEqualTo(SIN_2FA);
        assertThat(desafioDe(sesion)).isEmpty();
    }

    @Test
    void login_conDosFactores_reutilizaLaSesionAnonimaQueGuardaElTokenCsrf() throws Exception { // CP-2FA-338
        when(servicioAutenticacion.autenticar(any(), any())).thenReturn(CON_2FA);
        MockHttpSession anonima = new MockHttpSession();
        anonima.setAttribute("token-csrf", "valor");

        MvcResult resultado = mockMvc.perform(postJson(LOGIN, anonima, CUERPO_LOGIN)).andReturn();

        assertThat(resultado.getRequest().getSession(false)).isSameAs(anonima);
        assertThat(anonima.isInvalid()).isFalse();
        assertThat(anonima.getAttribute("token-csrf")).isEqualTo("valor");
        assertThat(desafioDe(anonima)).isPresent();
    }

    @Test
    void login_conDosFactores_conUnaSesionAutenticadaLaInvalida() throws Exception { // CP-2FA-339
        when(servicioAutenticacion.autenticar(any(), any())).thenReturn(CON_2FA);
        MockHttpSession autenticada = sesionAutenticada();

        MvcResult resultado = mockMvc.perform(postJson(LOGIN, autenticada, CUERPO_LOGIN)).andReturn();

        assertThat(autenticada.isInvalid()).isTrue();
        MockHttpSession nueva = (MockHttpSession) resultado.getRequest().getSession(false);
        assertThat(nueva).isNotSameAs(autenticada);
        assertThat(nueva.getAttribute(EstablecedorSesion.ATRIBUTO_USUARIO)).isNull();
        assertThat(desafioDe(nueva)).isPresent();
    }

    @Test
    void login_conCredencialesIncorrectas_noDejaDesafio() throws Exception { // CP-2FA-340
        when(servicioAutenticacion.autenticar(any(), any())).thenThrow(new CredencialesInvalidasException());
        MockHttpSession anonima = new MockHttpSession();

        mockMvc.perform(postJson(LOGIN, anonima, CUERPO_LOGIN)).andExpect(status().isUnauthorized());

        assertThat(desafioDe(anonima)).isEmpty();
    }

    // ------------------------------------------------------------------ paso 2: acierto

    @Test
    void verificar_conUnCodigoCorrecto_respondeComoUnLoginNormalYCreaLaSesionDefinitiva() throws Exception { // CP-2FA-341
        MockHttpSession pendiente = sesionConDesafio();

        MvcResult resultado = mockMvc.perform(postJson(VERIFICAR, pendiente, CUERPO_CODIGO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ID))
                .andExpect(jsonPath("$.email").value(EMAIL))
                .andExpect(jsonPath("$.nombre").value("Ana"))
                .andExpect(jsonPath("$.rol").value("CLIENTE"))
                .andExpect(jsonPath("$.avatar").value("avatar-01"))
                .andExpect(jsonPath("$.mensaje").isNotEmpty())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
                .andReturn();

        Map<String, Object> campos = JsonPath.read(cuerpoDe(resultado), "$");
        assertThat(campos).containsOnlyKeys("id", "email", "nombre", "rol", "avatar", "mensaje");
        verify(servicioDosFactores).verificarParaLogin(ID, CODIGO, CONTEXTO);
    }

    @Test
    void verificar_alAcertar_laSesionPendienteSeInvalidaYLaNuevaEstaAutenticadaSinDesafio() throws Exception { // CP-2FA-342
        MockHttpSession pendiente = sesionConDesafio();

        MvcResult resultado = mockMvc.perform(postJson(VERIFICAR, pendiente, CUERPO_CODIGO)).andReturn();

        MockHttpSession definitiva = (MockHttpSession) resultado.getRequest().getSession(false);
        // Nuevo identificador (sin fijacion de sesion) y el usuario ya sin el indicador del segundo factor
        assertThat(pendiente.isInvalid()).isTrue();
        assertThat(definitiva).isNotSameAs(pendiente);
        assertThat(definitiva.getId()).isNotEqualTo(pendiente.getId());
        assertThat(definitiva.getAttribute(EstablecedorSesion.ATRIBUTO_USUARIO)).isEqualTo(SIN_2FA);
        assertThat(desafioDe(definitiva)).isEmpty();
        assertThat(estado(definitiva, "/api/auth/me")).isEqualTo(200);
        // y la sesion pendiente ya no sirve para nada
        assertThat(estado(pendiente, "/api/auth/me")).isEqualTo(401);
    }

    @Test
    void verificar_elCodigoSeEntregaAlServicioTalCual_conSusEspacios() throws Exception { // CP-2FA-343
        mockMvc.perform(postJson(VERIFICAR, sesionConDesafio(), "{\"codigo\":\" 123 456 \"}")).andExpect(status().isOk());

        verify(servicioDosFactores).verificarParaLogin(ID, " 123 456 ", CONTEXTO);
    }

    @Test
    void verificar_laIpEsLaDelSocket_yElXForwardedForSeIgnora() throws Exception { // CP-2FA-344
        mockMvc.perform(postJson(VERIFICAR, sesionConDesafio(), CUERPO_CODIGO).header("X-Forwarded-For", "198.51.100.99"))
                .andExpect(status().isOk());

        verify(servicioDosFactores).verificarParaLogin(ID, CODIGO, CONTEXTO);
    }

    // ------------------------------------------------------------------ paso 2: acceso

    @Test
    void verificar_exigeTokenCsrf() throws Exception { // CP-2FA-345
        MockHttpSession pendiente = sesionConDesafio();

        mockMvc.perform(post(VERIFICAR).session(pendiente).with(desdeLaIp(IP_CLIENTE))
                .contentType(MediaType.APPLICATION_JSON).content(CUERPO_CODIGO)).andExpect(status().isForbidden());
        mockMvc.perform(postJson(VERIFICAR, pendiente, CUERPO_CODIGO).with(csrf().useInvalidToken()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(servicioDosFactores);
        assertThat(desafioDe(pendiente)).isPresent();
    }

    @Test
    void verificar_sinSesion_devuelve401SinLlamarAlServicioYLoAudita() throws Exception { // CP-2FA-346
        MvcResult resultado = mockMvc.perform(postJson(VERIFICAR, null, CUERPO_CODIGO))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.mensaje").isNotEmpty())
                .andReturn();

        assertThat(cuerpoDe(resultado)).doesNotContain(CODIGO);
        verifyNoInteractions(servicioDosFactores);
        verify(auditoria).registrarLoginSegundoFactorNoVigente(CONTEXTO);
    }

    @Test
    void verificar_conUnaSesionAutenticadaPeroSinDesafio_devuelve401YNoLaTocaNiLlamaAlServicio() throws Exception { // CP-2FA-347
        MockHttpSession autenticada = sesionAutenticada();

        mockMvc.perform(postJson(VERIFICAR, autenticada, CUERPO_CODIGO)).andExpect(status().isUnauthorized());

        verifyNoInteractions(servicioDosFactores);
        assertThat(autenticada.isInvalid()).isFalse();
        assertThat(estado(autenticada, "/api/auth/me")).isEqualTo(200);
    }

    @Test
    void verificar_conUnDesafioCaducado_devuelve401LoDescartaYNoLlamaAlServicio() throws Exception { // CP-2FA-348
        MockHttpSession pendiente = sesionConDesafio();
        pendiente.setAttribute(GestorDesafioSegundoFactor.ATRIBUTO_DESAFIO,
                new DesafioSegundoFactor(CON_2FA, Instant.now().minus(Duration.ofMinutes(10)), 0));

        mockMvc.perform(postJson(VERIFICAR, pendiente, CUERPO_CODIGO)).andExpect(status().isUnauthorized());

        verifyNoInteractions(servicioDosFactores);
        assertThat(pendiente.getAttribute(GestorDesafioSegundoFactor.ATRIBUTO_DESAFIO)).isNull();
        verify(auditoria).registrarLoginSegundoFactorNoVigente(CONTEXTO);
    }

    @Test
    void verificar_unaVezAcertado_elMismoDesafioNoSePuedeReutilizar() throws Exception { // CP-2FA-349
        MockHttpSession pendiente = sesionConDesafio();
        mockMvc.perform(postJson(VERIFICAR, pendiente, CUERPO_CODIGO)).andExpect(status().isOk());

        // Con la sesion pendiente (ya invalidada) y con la nueva: ninguna tiene un desafio
        mockMvc.perform(postJson(VERIFICAR, pendiente, CUERPO_CODIGO)).andExpect(status().isUnauthorized());

        verify(servicioDosFactores, times(1)).verificarParaLogin(any(), any(), any());
    }

    // ------------------------------------------------------------------ paso 2: fallos

    @Test
    void verificar_conUnCodigoIncorrecto_devuelve400ConElCampoYSumaUnIntento() throws Exception { // CP-2FA-350
        org.mockito.Mockito.doThrow(new DatosDosFactoresInvalidosException(
                Map.of("codigo", Set.of(CodigoError.CODIGO_INCORRECTO)))).when(servicioDosFactores)
                .verificarParaLogin(any(), any(), any());
        MockHttpSession pendiente = sesionConDesafio();

        MvcResult resultado = mockMvc.perform(postJson(VERIFICAR, pendiente, CUERPO_CODIGO))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores.codigo[0]").value("CODIGO_INCORRECTO"))
                .andReturn();

        assertThat(cuerpoDe(resultado)).doesNotContain(CODIGO);
        assertThat(desafioDe(pendiente).orElseThrow().intentosFallidos()).isEqualTo(1);
        assertThat(pendiente.isInvalid()).isFalse();
    }

    @Test
    void verificar_alQuintoCodigoFallido_seDescartaElDesafioYElSextoDa401() throws Exception { // CP-2FA-351
        org.mockito.Mockito.doThrow(new DatosDosFactoresInvalidosException(
                Map.of("codigo", Set.of(CodigoError.CODIGO_INCORRECTO)))).when(servicioDosFactores)
                .verificarParaLogin(any(), any(), any());
        MockHttpSession pendiente = sesionConDesafio();

        for (int i = 1; i <= 5; i++) {
            // Todos, tambien el quinto, son la respuesta del codigo incorrecto
            mockMvc.perform(postJson(VERIFICAR, pendiente, CUERPO_CODIGO)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errores.codigo[0]").value("CODIGO_INCORRECTO"));
        }

        assertThat(desafioDe(pendiente)).isEmpty();
        verify(auditoria).registrarLoginSegundoFactorAgotado(CONTEXTO, EMAIL, ID);
        mockMvc.perform(postJson(VERIFICAR, pendiente, CUERPO_CODIGO)).andExpect(status().isUnauthorized());
        verify(servicioDosFactores, times(5)).verificarParaLogin(any(), any(), any());
    }

    @Test
    void verificar_unCodigoVacioNoCuentaComoIntento() throws Exception { // CP-2FA-352
        org.mockito.Mockito.doThrow(new DatosDosFactoresInvalidosException(
                Map.of("codigo", Set.of(CodigoError.OBLIGATORIO)))).when(servicioDosFactores)
                .verificarParaLogin(any(), any(), any());
        MockHttpSession pendiente = sesionConDesafio();

        for (int i = 0; i < 8; i++) {
            mockMvc.perform(postJson(VERIFICAR, pendiente, "{\"codigo\":\"\"}")).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errores.codigo[0]").value("OBLIGATORIO"));
        }

        assertThat(desafioDe(pendiente).orElseThrow().intentosFallidos()).isZero();
    }

    @Test
    void verificar_conElLimiteAgotado_devuelve429ConservaElDesafioYNoSumaIntento() throws Exception { // CP-2FA-353
        org.mockito.Mockito.doThrow(new DemasiadosIntentosDosFactoresException()).when(servicioDosFactores)
                .verificarParaLogin(any(), any(), any());
        MockHttpSession pendiente = sesionConDesafio();

        MvcResult resultado = mockMvc.perform(postJson(VERIFICAR, pendiente, CUERPO_CODIGO))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.mensaje").isNotEmpty())
                .andReturn();

        assertThat(cuerpoDe(resultado)).doesNotContain(CODIGO, IP_CLIENTE, ID);
        assertThat(desafioDe(pendiente).orElseThrow().intentosFallidos()).isZero();
    }

    @Test
    void verificar_siLaCuentaCambioEntreLosDosPasos_devuelve401YDescartaElDesafio() throws Exception { // CP-2FA-354
        org.mockito.Mockito.doThrow(new SegundoFactorNoVigenteException()).when(servicioDosFactores)
                .verificarParaLogin(any(), any(), any());
        MockHttpSession pendiente = sesionConDesafio();

        mockMvc.perform(postJson(VERIFICAR, pendiente, CUERPO_CODIGO)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.mensaje").isNotEmpty());

        assertThat(desafioDe(pendiente)).isEmpty();
    }

    @Test
    void verificar_siElServicioFalla_devuelve503OEl500GenericosSinSumarIntento() throws Exception { // CP-2FA-355
        MockHttpSession pendiente = sesionConDesafio();
        org.mockito.Mockito.doThrow(new ServicioNoDisponibleException(null)).when(servicioDosFactores)
                .verificarParaLogin(any(), any(), any());

        MvcResult noDisponible = mockMvc.perform(postJson(VERIFICAR, pendiente, CUERPO_CODIGO))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andReturn();

        org.mockito.Mockito.doThrow(new IllegalStateException("detalle interno con " + CODIGO)).when(servicioDosFactores)
                .verificarParaLogin(any(), any(), any());
        MvcResult inesperado = mockMvc.perform(postJson(VERIFICAR, pendiente, CUERPO_CODIGO))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andReturn();

        assertThat(cuerpoDe(noDisponible) + cuerpoDe(inesperado)).doesNotContain(CODIGO, "detalle interno");
        assertThat(desafioDe(pendiente).orElseThrow().intentosFallidos()).isZero();
    }

    // ------------------------------------------------------------------ forma de la peticion

    private static Stream<Arguments> cuerposRechazados() {
        return Stream.of(
                arguments("campo desconocido", "{\"codigo\":\"123456\",\"idUsuario\":\"1\"}"),
                arguments("el email en el cuerpo", "{\"codigo\":\"123456\",\"email\":\"otra@ejemplo.es\"}"),
                arguments("codigo que es un objeto", "{\"codigo\":{\"a\":1}}"),
                arguments("JSON roto", "{\"codigo\":"),
                arguments("sin cuerpo", ""));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cuerposRechazados")
    void verificar_cuerposQueNoSonValidos_devuelven400YNoLlamanAlServicio(String descripcion, String cuerpo)
            throws Exception { // CP-2FA-356
        mockMvc.perform(postJson(VERIFICAR, sesionConDesafio(), cuerpo)).andExpect(status().isBadRequest());

        verifyNoInteractions(servicioDosFactores);
    }

    @Test
    void verificar_elCodigoEnLaUrl_seRechazaAunqueElCuerpoSeaValido() throws Exception { // CP-2FA-357
        mockMvc.perform(postJson(VERIFICAR, sesionConDesafio(), CUERPO_CODIGO).param("codigo", CODIGO))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(servicioDosFactores);
    }

    @Test
    void verificar_otrosTiposDeContenido_devuelven415() throws Exception { // CP-2FA-358
        MockHttpSession pendiente = sesionConDesafio();

        mockMvc.perform(post(VERIFICAR).session(pendiente).with(csrf()).contentType(MediaType.TEXT_PLAIN)
                .content(CUERPO_CODIGO)).andExpect(status().isUnsupportedMediaType());
        mockMvc.perform(post(VERIFICAR).session(pendiente).with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).content("codigo=123456"))
                .andExpect(status().isUnsupportedMediaType());

        verifyNoInteractions(servicioDosFactores);
    }

    @Test
    void verificar_cuerpoDesproporcionado_devuelve413() throws Exception { // CP-2FA-359
        mockMvc.perform(postJson(VERIFICAR, sesionConDesafio(), "{\"codigo\":\"" + "1".repeat(20_000) + "\"}"))
                .andExpect(status().is(413));

        verifyNoInteractions(servicioDosFactores);
    }

    @Test
    void verificar_elMetodoGetNoExiste_yDaLa401DeCualquierGetAnonimoDeLaApi() throws Exception { // CP-2FA-360
        assertThat(estado(sesionConDesafio(), VERIFICAR)).isEqualTo(401);
        assertThat(mockMvc.perform(get(VERIFICAR)).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    // ------------------------------------------------------------------ nada sensible

    @Test
    void elDtoNoMuestraElCodigoEnSuToString() { // CP-2FA-361
        assertThat(new SolicitudSegundoFactorLoginDTO(CODIGO).toString()).doesNotContain(CODIGO);
    }

    @Test
    void elDesafioNoSeComparteEntreSesiones() throws Exception { // CP-2FA-362
        MockHttpSession deAna = sesionConDesafio();
        MockHttpSession otra = new MockHttpSession();

        // Quien tiene su propia sesion (sin desafio) no puede completar el login de otra persona
        mockMvc.perform(postJson(VERIFICAR, otra, CUERPO_CODIGO)).andExpect(status().isUnauthorized());

        verify(servicioDosFactores, never()).verificarParaLogin(any(), any(), any());
        assertThat(desafioDe(deAna)).isPresent();
        assertThat(Collections.list(otra.getAttributeNames())).doesNotContain(GestorDesafioSegundoFactor.ATRIBUTO_DESAFIO);
    }
}
