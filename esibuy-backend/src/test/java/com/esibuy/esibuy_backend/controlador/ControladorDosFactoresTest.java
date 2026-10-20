package com.esibuy.esibuy_backend.controlador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
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
import com.esibuy.esibuy_backend.dto.EstadoDosFactoresDTO;
import com.esibuy.esibuy_backend.dto.RespuestaActivacionDosFactoresDTO;
import com.esibuy.esibuy_backend.dto.RespuestaCodigosRecuperacionDTO;
import com.esibuy.esibuy_backend.dto.SolicitudActivacionDosFactoresDTO;
import com.esibuy.esibuy_backend.dto.SolicitudConfirmacionDosFactoresDTO;
import com.esibuy.esibuy_backend.dto.SolicitudVerificacionDosFactoresDTO;
import com.esibuy.esibuy_backend.excepcion.CodigoError;
import com.esibuy.esibuy_backend.excepcion.DatosDosFactoresInvalidosException;
import com.esibuy.esibuy_backend.excepcion.DemasiadosIntentosDosFactoresException;
import com.esibuy.esibuy_backend.excepcion.OperacionNoPermitidaException;
import com.esibuy.esibuy_backend.excepcion.ServicioNoDisponibleException;
import com.esibuy.esibuy_backend.excepcion.UsuarioNoEncontradoException;
import com.esibuy.esibuy_backend.modelo.Rol;
import com.esibuy.esibuy_backend.seguridad.ConfiguracionSeguridad;
import com.esibuy.esibuy_backend.seguridad.EstablecedorSesion;
import com.esibuy.esibuy_backend.servicio.ContextoPeticion;
import com.esibuy.esibuy_backend.servicio.ResultadoAutenticacion;
import com.esibuy.esibuy_backend.servicio.ServicioDosFactores;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.HttpSession;

/**
 * Endpoints del segundo factor (/api/2fa) con la seguridad real y el servicio mockeado (HdU 2FA, fase 2).
 * Casos: CP-2FA-244 a CP-2FA-265.
 *
 * Decisiones que fijan estas pruebas (el equipo puede cambiarlas, pero deben cambiar a la vez aqui):
 *  - GET  /api/2fa                            -> 200 {activo, codigosRecuperacionDisponibles}
 *  - POST /api/2fa/activacion                 {contrasena} -> 200 {uri, secreto}
 *  - POST /api/2fa/activacion/confirmar       {codigo} -> 200 {codigosRecuperacion: [...]}
 *  - POST /api/2fa/desactivacion              {contrasena, codigo} -> 204
 *  - POST /api/2fa/codigos-recuperacion       {contrasena, codigo} -> 200 {codigosRecuperacion: [...]}
 *  - Cuelgan de /api/2fa y no de /api/auth: todo /api/auth permite POST anonimo, y estas operaciones exigen sesion
 *    (401 sin ella), ademas de token CSRF (403 sin el). El usuario es siempre el de la sesion: el cuerpo nunca lleva su id
 *    (un campo desconocido es un 400), asi que nadie puede tocar el 2FA de otro.
 *  - Tras activar, desactivar o regenerar los codigos con exito, la sesion se RENUEVA: se invalida la actual y se crea una
 *    nueva con el mismo usuario (cambia el identificador de sesion, buena practica tras un cambio de seguridad). Si falla,
 *    la sesion no se toca. Iniciar la activacion no la renueva (todavia no ha cambiado nada).
 *  - Errores: 400 {"errores": {campo: [codigos]}}, 404, 409, 429, 503 y 500 con los mismos formatos que el resto de la API.
 *    Ninguna respuesta repite la contrasena ni el codigo. Las respuestas con secretos no se guardan en cache.
 *  - Se rechazan campos desconocidos, la contrasena o el codigo en la URL (acabarian en logs), otros tipos de contenido
 *    (415), cuerpos desproporcionados (413) y los metodos que no son los de cada ruta (405).
 *  - La IP que recibe el servicio es la del socket; X-Forwarded-For solo se respeta desde proxies de confianza.
 *
 * Terminada: ya no lleva la etiqueta pendiente-2fa y entra en la regresion.
 */
@WebMvcTest(ControladorDosFactores.class)
@Import({ConfiguracionSeguridad.class, ConfiguracionAplicacion.class, ConfiguracionLogin.class})
class ControladorDosFactoresTest {

    private static final String RAIZ = "/api/2fa";
    private static final String ACTIVACION = RAIZ + "/activacion";
    private static final String CONFIRMAR = RAIZ + "/activacion/confirmar";
    private static final String DESACTIVACION = RAIZ + "/desactivacion";
    private static final String CODIGOS = RAIZ + "/codigos-recuperacion";

    private static final ResultadoAutenticacion USUARIO = new ResultadoAutenticacion(
            "665f1c2e9b1e8a3d4c5b6a79", "ana.garcia@ejemplo.es", "Ana", Rol.CLIENTE);
    private static final String CONTRASENA = "Clave-De-Prueba-42!";
    private static final String CODIGO = "123456";
    private static final String IP_CLIENTE = "203.0.113.7";
    private static final String USER_AGENT = "JUnit-UA";
    private static final ContextoPeticion CONTEXTO = new ContextoPeticion(IP_CLIENTE, USER_AGENT);
    private static final String SECRETO = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    private static final String URI = "otpauth://totp/ESIBuy:ana.garcia%40ejemplo.es?secret=" + SECRETO + "&issuer=ESIBuy";
    private static final List<String> CODIGOS_RECUPERACION = List.of("ABCD-EFGH-2345-6723", "WXYZ-2345-6723-ABCD");

    private static final String CUERPO_ACTIVACION = "{\"contrasena\":\"" + CONTRASENA + "\"}";
    private static final String CUERPO_CONFIRMAR = "{\"codigo\":\"" + CODIGO + "\"}";
    private static final String CUERPO_VERIFICACION = "{\"contrasena\":\"" + CONTRASENA + "\",\"codigo\":\"" + CODIGO + "\"}";

    @Autowired
    private WebApplicationContext contexto;
    @Autowired
    private EstablecedorSesion establecedorSesion;
    @MockitoBean
    private ServicioDosFactores servicio;

    private MockMvc mockMvc;

    @BeforeEach
    void prepararMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(contexto)
                .addFilters(new FiltroCorrelacionId())
                .apply(springSecurity())
                .build();
    }

    // ------------------------------------------------------------------ utilidades

    /** Una sesion tal y como la deja un login correcto. */
    private MockHttpSession sesionIniciada() {
        MockHttpServletRequest peticion = new MockHttpServletRequest();
        establecedorSesion.establecer(peticion, USUARIO);
        return (MockHttpSession) peticion.getSession(false);
    }

    private static RequestPostProcessor desdeLaIp(String ip) {
        return peticion -> {
            peticion.setRemoteAddr(ip);
            return peticion;
        };
    }

    private static MockHttpServletRequestBuilder conSesion(MockHttpServletRequestBuilder base, MockHttpSession sesion) {
        return base.session(sesion).with(desdeLaIp(IP_CLIENTE)).header(HttpHeaders.USER_AGENT, USER_AGENT);
    }

    private static MockHttpServletRequestBuilder postJson(String ruta, MockHttpSession sesion, String cuerpo) {
        return conSesion(post(ruta), sesion).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(cuerpo);
    }

    private static String cuerpoDe(MvcResult resultado) throws Exception {
        return resultado.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** Las operaciones que, si salen bien, renuevan la sesion: ruta, cuerpo y como hacer que el servicio falle. */
    private static Stream<Arguments> operacionesQueRenuevanLaSesion() {
        return Stream.of(
                arguments("confirmar la activacion", CONFIRMAR, CUERPO_CONFIRMAR),
                arguments("desactivar", DESACTIVACION, CUERPO_VERIFICACION),
                arguments("regenerar los codigos", CODIGOS, CUERPO_VERIFICACION));
    }

    private static Stream<Arguments> todasLasOperaciones() {
        return Stream.of(
                arguments("iniciar la activacion", ACTIVACION, CUERPO_ACTIVACION),
                arguments("confirmar la activacion", CONFIRMAR, CUERPO_CONFIRMAR),
                arguments("desactivar", DESACTIVACION, CUERPO_VERIFICACION),
                arguments("regenerar los codigos", CODIGOS, CUERPO_VERIFICACION));
    }

    /** Hace que el servicio falle con esa excepcion en la operacion que corresponda a la ruta. */
    private void hacerQueFalle(String ruta, RuntimeException excepcion) {
        switch (ruta) {
            case ACTIVACION -> when(servicio.iniciarActivacion(any(), any(), any())).thenThrow(excepcion);
            case CONFIRMAR -> when(servicio.confirmarActivacion(any(), any(), any())).thenThrow(excepcion);
            case DESACTIVACION -> doThrow(excepcion).when(servicio).desactivar(any(), any(), any(), any());
            default -> when(servicio.regenerarCodigos(any(), any(), any(), any())).thenThrow(excepcion);
        }
    }

    private void comprobarQueNoSeHaLlamadoAlServicio() {
        verifyNoInteractions(servicio);
    }

    // ------------------------------------------------------------------ acceso: sesion y CSRF

    @ParameterizedTest(name = "{0}")
    @MethodSource("todasLasOperaciones")
    void sinSesion_devuelve401YNoLlamaAlServicio(String descripcion, String ruta, String cuerpo) throws Exception { // CP-2FA-244
        mockMvc.perform(post(ruta).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                .andExpect(status().isUnauthorized());

        comprobarQueNoSeHaLlamadoAlServicio();
    }

    @Test
    void sinSesion_laConsultaDelEstadoTambienDevuelve401() throws Exception { // CP-2FA-244
        mockMvc.perform(get(RAIZ)).andExpect(status().isUnauthorized());

        comprobarQueNoSeHaLlamadoAlServicio();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("todasLasOperaciones")
    void conSesionPeroSinTokenCsrf_devuelve403YNoLlamaAlServicio(String descripcion, String ruta, String cuerpo)
            throws Exception { // CP-2FA-245
        MockHttpSession sesion = sesionIniciada();

        mockMvc.perform(conSesion(post(ruta), sesion).contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                .andExpect(status().isForbidden());
        mockMvc.perform(postJson(ruta, sesion, cuerpo).with(csrf().useInvalidToken()))
                .andExpect(status().isForbidden());

        comprobarQueNoSeHaLlamadoAlServicio();
    }

    // ------------------------------------------------------------------ GET estado

    @Test
    void estado_devuelve200ConElEstadoYSoloEsosCampos() throws Exception { // CP-2FA-246
        when(servicio.estado(USUARIO.id())).thenReturn(new EstadoDosFactoresDTO(true, 7));

        MvcResult resultado = mockMvc.perform(conSesion(get(RAIZ), sesionIniciada()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activo").value(true))
                .andExpect(jsonPath("$.codigosRecuperacionDisponibles").value(7))
                .andReturn();

        Map<String, Object> campos = JsonPath.read(cuerpoDe(resultado), "$");
        assertThat(campos).containsOnlyKeys("activo", "codigosRecuperacionDisponibles");
    }

    // ------------------------------------------------------------------ cada operacion

    @Test
    void iniciarActivacion_devuelve200ConLaUriYElSecretoSinCacheYSinRenovarLaSesion() throws Exception { // CP-2FA-247
        when(servicio.iniciarActivacion(USUARIO.id(), CONTRASENA, CONTEXTO))
                .thenReturn(new RespuestaActivacionDosFactoresDTO(URI, SECRETO));
        MockHttpSession sesion = sesionIniciada();

        MvcResult resultado = mockMvc.perform(postJson(ACTIVACION, sesion, CUERPO_ACTIVACION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uri").value(URI))
                .andExpect(jsonPath("$.secreto").value(SECRETO))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
                .andReturn();

        Map<String, Object> campos = JsonPath.read(cuerpoDe(resultado), "$");
        assertThat(campos).containsOnlyKeys("uri", "secreto");
        verify(servicio).iniciarActivacion(USUARIO.id(), CONTRASENA, CONTEXTO);
        // Todavia no ha cambiado nada: la misma sesion sigue valiendo
        assertThat(sesion.isInvalid()).isFalse();
        assertThat(resultado.getRequest().getSession(false)).isSameAs(sesion);
    }

    @Test
    void confirmarActivacion_devuelve200ConLosCodigosDeRecuperacionSinCacheYRenuevaLaSesion() throws Exception { // CP-2FA-248
        when(servicio.confirmarActivacion(USUARIO.id(), CODIGO, CONTEXTO))
                .thenReturn(new RespuestaCodigosRecuperacionDTO(CODIGOS_RECUPERACION));
        MockHttpSession sesion = sesionIniciada();

        MvcResult resultado = mockMvc.perform(postJson(CONFIRMAR, sesion, CUERPO_CONFIRMAR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.codigosRecuperacion[0]").value(CODIGOS_RECUPERACION.get(0)))
                .andExpect(jsonPath("$.codigosRecuperacion[1]").value(CODIGOS_RECUPERACION.get(1)))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
                .andReturn();

        Map<String, Object> campos = JsonPath.read(cuerpoDe(resultado), "$");
        assertThat(campos).containsOnlyKeys("codigosRecuperacion");
        verify(servicio).confirmarActivacion(USUARIO.id(), CODIGO, CONTEXTO);
        comprobarSesionRenovada(sesion, resultado);
    }

    @Test
    void desactivar_devuelve204YRenuevaLaSesion() throws Exception { // CP-2FA-249
        MockHttpSession sesion = sesionIniciada();

        MvcResult resultado = mockMvc.perform(postJson(DESACTIVACION, sesion, CUERPO_VERIFICACION))
                .andExpect(status().isNoContent())
                .andReturn();

        assertThat(cuerpoDe(resultado)).isEmpty();
        verify(servicio).desactivar(USUARIO.id(), CONTRASENA, CODIGO, CONTEXTO);
        comprobarSesionRenovada(sesion, resultado);
    }

    @Test
    void regenerarCodigos_devuelve200ConLosCodigosNuevosSinCacheYRenuevaLaSesion() throws Exception { // CP-2FA-250
        when(servicio.regenerarCodigos(USUARIO.id(), CONTRASENA, CODIGO, CONTEXTO))
                .thenReturn(new RespuestaCodigosRecuperacionDTO(CODIGOS_RECUPERACION));
        MockHttpSession sesion = sesionIniciada();

        MvcResult resultado = mockMvc.perform(postJson(CODIGOS, sesion, CUERPO_VERIFICACION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.codigosRecuperacion.length()").value(2))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
                .andReturn();

        verify(servicio).regenerarCodigos(USUARIO.id(), CONTRASENA, CODIGO, CONTEXTO);
        comprobarSesionRenovada(sesion, resultado);
    }

    /** La sesion anterior queda invalidada y hay otra nueva, con el mismo usuario y un identificador distinto. */
    private static void comprobarSesionRenovada(MockHttpSession anterior, MvcResult resultado) {
        HttpSession nueva = resultado.getRequest().getSession(false);
        assertThat(anterior.isInvalid()).isTrue();
        assertThat(nueva).isNotNull().isNotSameAs(anterior);
        assertThat(nueva.getId()).isNotEqualTo(anterior.getId());
        assertThat(nueva.getAttribute(EstablecedorSesion.ATRIBUTO_USUARIO)).isEqualTo(USUARIO);
    }

    // ------------------------------------------------------------------ errores

    @ParameterizedTest(name = "{0}")
    @MethodSource("operacionesQueRenuevanLaSesion")
    void siElServicioRechazaLosDatos_devuelve400ConElCampoYNoRenuevaLaSesion(String descripcion, String ruta,
            String cuerpo) throws Exception { // CP-2FA-251
        hacerQueFalle(ruta, new DatosDosFactoresInvalidosException(
                Map.of("codigo", Set.of(CodigoError.CODIGO_INCORRECTO))));
        MockHttpSession sesion = sesionIniciada();

        MvcResult resultado = mockMvc.perform(postJson(ruta, sesion, cuerpo))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores.codigo[0]").value("CODIGO_INCORRECTO"))
                .andReturn();

        assertThat(cuerpoDe(resultado)).doesNotContain(CONTRASENA, CODIGO);
        assertThat(sesion.isInvalid()).isFalse();
        assertThat(resultado.getRequest().getSession(false)).isSameAs(sesion);
    }

    @Test
    void siLaContrasenaEsIncorrecta_devuelve400ConElCampoContrasena() throws Exception { // CP-2FA-252
        when(servicio.iniciarActivacion(any(), any(), any())).thenThrow(new DatosDosFactoresInvalidosException(
                Map.of("contrasena", Set.of(CodigoError.CONTRASENA_INCORRECTA))));

        mockMvc.perform(postJson(ACTIVACION, sesionIniciada(), CUERPO_ACTIVACION))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores.contrasena[0]").value("CONTRASENA_INCORRECTA"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("todasLasOperaciones")
    void operacionNoPermitida_devuelve409(String descripcion, String ruta, String cuerpo) throws Exception { // CP-2FA-253
        hacerQueFalle(ruta, new OperacionNoPermitidaException());
        MockHttpSession sesion = sesionIniciada();

        mockMvc.perform(postJson(ruta, sesion, cuerpo)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensaje").isNotEmpty());

        assertThat(sesion.isInvalid()).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("todasLasOperaciones")
    void demasiadosFallos_devuelve429SinDetalles(String descripcion, String ruta, String cuerpo) throws Exception { // CP-2FA-254
        hacerQueFalle(ruta, new DemasiadosIntentosDosFactoresException());

        MvcResult resultado = mockMvc.perform(postJson(ruta, sesionIniciada(), cuerpo))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.mensaje").isNotEmpty())
                .andReturn();

        assertThat(cuerpoDe(resultado)).doesNotContain(CONTRASENA, CODIGO, IP_CLIENTE);
    }

    @Test
    void usuarioInexistente_devuelve404() throws Exception { // CP-2FA-255
        when(servicio.estado(any())).thenThrow(new UsuarioNoEncontradoException());

        mockMvc.perform(conSesion(get(RAIZ), sesionIniciada())).andExpect(status().isNotFound());
    }

    @Test
    void servicioNoDisponible_devuelve503ConCorrelationId() throws Exception { // CP-2FA-256
        when(servicio.iniciarActivacion(any(), any(), any())).thenThrow(new ServicioNoDisponibleException(null));

        MvcResult resultado = mockMvc.perform(postJson(ACTIVACION, sesionIniciada(), CUERPO_ACTIVACION))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andReturn();

        assertThat(cuerpoDe(resultado)).doesNotContain(CONTRASENA);
    }

    @Test
    void errorInesperado_devuelve500GenericoConCorrelationIdYSinDetalles() throws Exception { // CP-2FA-256
        when(servicio.iniciarActivacion(any(), any(), any()))
                .thenThrow(new IllegalStateException("detalle interno con " + CONTRASENA));

        MvcResult resultado = mockMvc.perform(postJson(ACTIVACION, sesionIniciada(), CUERPO_ACTIVACION))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andReturn();

        assertThat(cuerpoDe(resultado)).doesNotContain(CONTRASENA, "detalle interno");
    }

    // ------------------------------------------------------------------ forma de la peticion

    private static Stream<Arguments> cuerposRechazados() {
        return Stream.of(
                arguments("campo desconocido (el id del usuario)", ACTIVACION,
                        "{\"contrasena\":\"" + CONTRASENA + "\",\"idUsuario\":\"665f1c2e9b1e8a3d4c5b6a80\"}"),
                arguments("campo desconocido al confirmar", CONFIRMAR, "{\"codigo\":\"123456\",\"idUsuario\":\"1\"}"),
                arguments("campo desconocido al desactivar", DESACTIVACION,
                        "{\"contrasena\":\"x\",\"codigo\":\"123456\",\"forzar\":true}"),
                // (un numero se convierte en texto, como en el login: no es un error; un objeto o una lista si)
                arguments("contrasena que es un objeto", ACTIVACION, "{\"contrasena\":{\"a\":1}}"),
                arguments("codigo que no es texto", CONFIRMAR, "{\"codigo\":[\"1\",\"2\"]}"),
                arguments("JSON roto", DESACTIVACION, "{\"contrasena\":"),
                arguments("sin cuerpo", CODIGOS, ""));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cuerposRechazados")
    void cuerposQueNoSonValidos_devuelven400YNoLlamanAlServicio(String descripcion, String ruta, String cuerpo)
            throws Exception { // CP-2FA-257
        mockMvc.perform(postJson(ruta, sesionIniciada(), cuerpo)).andExpect(status().isBadRequest());

        comprobarQueNoSeHaLlamadoAlServicio();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("todasLasOperaciones")
    void laContrasenaOElCodigoEnLaUrl_seRechazan(String descripcion, String ruta, String cuerpo) throws Exception { // CP-2FA-258
        // En la URL acabarian en logs de proxys y en el historial, aunque el cuerpo sea valido
        mockMvc.perform(postJson(ruta, sesionIniciada(), cuerpo).param("contrasena", CONTRASENA))
                .andExpect(status().isBadRequest());
        mockMvc.perform(postJson(ruta, sesionIniciada(), cuerpo).param("codigo", CODIGO))
                .andExpect(status().isBadRequest());

        comprobarQueNoSeHaLlamadoAlServicio();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("todasLasOperaciones")
    void otrosTiposDeContenido_devuelven415(String descripcion, String ruta, String cuerpo) throws Exception { // CP-2FA-259
        mockMvc.perform(conSesion(post(ruta), sesionIniciada()).with(csrf()).contentType(MediaType.TEXT_PLAIN)
                .content(cuerpo)).andExpect(status().isUnsupportedMediaType());
        mockMvc.perform(conSesion(post(ruta), sesionIniciada()).with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).content("contrasena=x&codigo=123456"))
                .andExpect(status().isUnsupportedMediaType());

        comprobarQueNoSeHaLlamadoAlServicio();
    }

    @Test
    void cuerpoDesproporcionado_devuelve413() throws Exception { // CP-2FA-260
        mockMvc.perform(postJson(ACTIVACION, sesionIniciada(), "{\"contrasena\":\"" + "a".repeat(20_000) + "\"}"))
                .andExpect(status().is(413));

        comprobarQueNoSeHaLlamadoAlServicio();
    }

    @Test
    void metodosQueNoSonLosDeCadaRuta_devuelven405() throws Exception { // CP-2FA-261
        MockHttpSession sesion = sesionIniciada();

        mockMvc.perform(conSesion(get(ACTIVACION), sesion)).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(conSesion(get(DESACTIVACION), sesion)).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(postJson(RAIZ, sesion, "{}")).andExpect(status().isMethodNotAllowed());

        comprobarQueNoSeHaLlamadoAlServicio();
    }

    @Test
    void laIpEsLaDelSocket_yElXForwardedForSeIgnora() throws Exception { // CP-2FA-262
        when(servicio.iniciarActivacion(any(), any(), any()))
                .thenReturn(new RespuestaActivacionDosFactoresDTO(URI, SECRETO));

        mockMvc.perform(postJson(ACTIVACION, sesionIniciada(), CUERPO_ACTIVACION)
                .header("X-Forwarded-For", "198.51.100.99")).andExpect(status().isOk());

        verify(servicio).iniciarActivacion(USUARIO.id(), CONTRASENA, CONTEXTO);
    }

    @Test
    void laContrasenaSeEntregaAlServicioTalCual_conSusEspacios() throws Exception { // CP-2FA-263
        String rara = "  \"Una\" clave \\ con ñ y 😀  ";
        when(servicio.iniciarActivacion(any(), any(), any()))
                .thenReturn(new RespuestaActivacionDosFactoresDTO(URI, SECRETO));

        mockMvc.perform(postJson(ACTIVACION, sesionIniciada(),
                "{\"contrasena\":\"  \\\"Una\\\" clave \\\\ con ñ y 😀  \"}")).andExpect(status().isOk());

        verify(servicio).iniciarActivacion(USUARIO.id(), rara, CONTEXTO);
    }

    // ------------------------------------------------------------------ nada sensible en los textos

    @Test
    void losDtoNoMuestranContrasenasCodigosNiSecretosEnSuToString() { // CP-2FA-264
        List<String> textos = List.of(
                new SolicitudActivacionDosFactoresDTO(CONTRASENA).toString(),
                new SolicitudConfirmacionDosFactoresDTO(CODIGO).toString(),
                new SolicitudVerificacionDosFactoresDTO(CONTRASENA, CODIGO).toString(),
                new RespuestaActivacionDosFactoresDTO(URI, SECRETO).toString(),
                new RespuestaCodigosRecuperacionDTO(CODIGOS_RECUPERACION).toString());

        assertThat(textos).allSatisfy(texto -> assertThat(texto)
                .doesNotContain(CONTRASENA, CODIGO, SECRETO, "ABCD-EFGH", "WXYZ-2345", "otpauth"));
    }

    @Test
    void elEstadoSiSePuedeMostrar() { // CP-2FA-265
        assertThat(new EstadoDosFactoresDTO(true, 7).toString()).contains("true", "7");
    }
}
