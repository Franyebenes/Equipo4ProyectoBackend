package com.esibuy.esibuy_backend.controlador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.esibuy.esibuy_backend.configuracion.ConfiguracionAplicacion;
import com.esibuy.esibuy_backend.configuracion.ConfiguracionLogin;
import com.esibuy.esibuy_backend.configuracion.FiltroCorrelacionId;
import com.esibuy.esibuy_backend.dto.RespuestaRegistroDosFactoresDTO;
import com.esibuy.esibuy_backend.dto.SolicitudRegistroClienteDTO;
import com.esibuy.esibuy_backend.dto.SolicitudRegistroVendedorDTO;
import com.esibuy.esibuy_backend.excepcion.CodigoError;
import com.esibuy.esibuy_backend.excepcion.DatosRegistroInvalidosException;
import com.esibuy.esibuy_backend.excepcion.RegistroNoCompletadoException;
import com.esibuy.esibuy_backend.excepcion.ServicioNoDisponibleException;
import com.esibuy.esibuy_backend.seguridad.ConfiguracionSeguridad;
import com.esibuy.esibuy_backend.seguridad.GestorRegistroDosFactores;
import com.esibuy.esibuy_backend.seguridad.PendienteRegistroDosFactores;
import com.esibuy.esibuy_backend.servicio.AuditoriaSeguridad;
import com.esibuy.esibuy_backend.servicio.ContextoPeticion;
import com.esibuy.esibuy_backend.servicio.ServicioRegistro;
import com.esibuy.esibuy_backend.util.ConstructorSolicitudCliente;
import com.esibuy.esibuy_backend.util.ConstructorSolicitudVendedor;
import com.jayway.jsonpath.JsonPath;

import tools.jackson.databind.ObjectMapper;

/**
 * Registro final con 2FA, con la seguridad real y el servicio mockeado (HdU 2FA en el registro, fase B).
 * Casos: CP-2FA-484 a CP-2FA-498.
 *
 * ControladorAuth (POST /api/auth/registro) ahora trabaja con el secreto pendiente de la sesion (GestorRegistroDosFactores).
 * Decisiones que fijan estas pruebas (el equipo puede cambiarlas, pero deben cambiar a la vez aqui):
 *  - El registro ya guarda estado en la sesion, asi que EXIGE token CSRF (antes estaba exento): sin el, 403 y no se llama al servicio.
 *  - El controlador pasa al servicio el secreto pendiente VIGENTE de la sesion (o null si no hay) y el contexto de la peticion
 *    (la IP del socket y el user agent); es el servicio quien decide si vale para ese correo.
 *  - Exito (201): los datos de siempre mas "codigosRecuperacion", sin cache, sin repetir el codigo TOTP ni la contrasena; y el
 *    secreto pendiente se descarta (no sirve para un segundo registro).
 *  - CODIGO_INCORRECTO: 400 y suma un fallo al pendiente; al 5.o (el maximo del desafio) el pendiente se descarta, se audita
 *    REGISTRO_DOS_FACTORES_AGOTADO y esa respuesta ya es ACTIVACION_NO_VIGENTE, para que el cliente vuelva al primer paso.
 *  - ACTIVACION_NO_VIGENTE: 400 y se descarta el pendiente.
 *  - Cualquier otro error (formato, dominio, correo repetido, 503, 500) NO toca el pendiente ni cuenta como fallo: la persona
 *    corrige y reintenta sin volver a escanear el QR.
 *  - Un codigo que no es texto es un 400 sin llamar al servicio.
 * Los demas errores del registro (campos desconocidos, 409, 503, 500) siguen cubiertos por ControladorAuthTest.
 */
@WebMvcTest(ControladorAuth.class)
@Import({ConfiguracionSeguridad.class, ConfiguracionAplicacion.class, ConfiguracionLogin.class})
class ControladorAuthDosFactoresTest {

    private static final String RUTA = "/api/auth/registro";
    private static final String EMAIL = ConstructorSolicitudCliente.EMAIL_POR_DEFECTO;
    private static final String CODIGO = ConstructorSolicitudCliente.CODIGO_DOS_FACTORES_POR_DEFECTO;
    private static final String CIFRADO = "1:" + "A".repeat(64);
    private static final String IP_CLIENTE = "203.0.113.7";
    private static final String USER_AGENT = "JUnit-UA";
    private static final ContextoPeticion CONTEXTO = new ContextoPeticion(IP_CLIENTE, USER_AGENT);
    private static final List<String> CODIGOS = List.of("ABCD-EFGH-IJKL-MNOP", "QRST-UVWX-YZ23-4567");
    private static final RespuestaRegistroDosFactoresDTO RESPUESTA = new RespuestaRegistroDosFactoresDTO(
            "id-cliente-1", EMAIL, "Ana", "Tu cuenta se ha creado correctamente", CODIGOS);

    @Autowired
    private WebApplicationContext contexto;
    @Autowired
    private GestorRegistroDosFactores gestor;
    @Autowired
    private ObjectMapper objectMapper;
    @MockitoBean
    private ServicioRegistro servicioRegistro;
    @MockitoBean
    private AuditoriaSeguridad auditoria;

    private MockMvc mockMvc;

    @BeforeEach
    void prepararMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(contexto)
                .addFilters(new FiltroCorrelacionId())
                .apply(springSecurity())
                .build();
        when(servicioRegistro.registrarCliente(any(SolicitudRegistroClienteDTO.class), any(), any())).thenReturn(RESPUESTA);
        when(servicioRegistro.registrarVendedor(any(SolicitudRegistroVendedorDTO.class), any(), any())).thenReturn(RESPUESTA);
    }

    // ------------------------------------------------------------------ utilidades

    private static RequestPostProcessor desdeLaIp(String ip) {
        return peticion -> {
            peticion.setRemoteAddr(ip);
            return peticion;
        };
    }

    private MockHttpSession sesionConPendiente() {
        MockHttpSession sesion = new MockHttpSession();
        MockHttpServletRequest peticion = new MockHttpServletRequest();
        peticion.setSession(sesion);
        gestor.iniciar(peticion, EMAIL, CIFRADO);
        return sesion;
    }

    private Optional<PendienteRegistroDosFactores> pendienteDe(MockHttpSession sesion) {
        MockHttpServletRequest peticion = new MockHttpServletRequest();
        peticion.setSession(sesion);
        return gestor.vigente(peticion);
    }

    private MockHttpServletRequestBuilder registro(MockHttpSession sesion, String cuerpo, boolean conCsrf) {
        MockHttpServletRequestBuilder peticion = post(RUTA).with(desdeLaIp(IP_CLIENTE))
                .header(HttpHeaders.USER_AGENT, USER_AGENT).contentType(MediaType.APPLICATION_JSON).content(cuerpo);
        if (conCsrf) {
            peticion = peticion.with(csrf());
        }
        return sesion == null ? peticion : peticion.session(sesion);
    }

    private ResultActions enviar(MockHttpSession sesion, String cuerpo) throws Exception {
        return mockMvc.perform(registro(sesion, cuerpo, true));
    }

    private String cuerpoCliente() {
        return objectMapper.writeValueAsString(ConstructorSolicitudCliente.unaSolicitudValida().construir());
    }

    private String cuerpoVendedor() {
        return objectMapper.writeValueAsString(ConstructorSolicitudVendedor.unaSolicitudValida().construir());
    }

    private static DatosRegistroInvalidosException errorDeCodigo(CodigoError codigo) {
        return new DatosRegistroInvalidosException(Map.of("codigoDosFactores", Set.of(codigo)));
    }

    private static String cuerpoDe(MvcResult resultado) throws Exception {
        return resultado.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ acierto

    @Test
    void registrarCliente_conSecretoPendiente_devuelve201ConLosCodigosDeRecuperacionSinCache() throws Exception { // CP-2FA-484
        MockHttpSession sesion = sesionConPendiente();
        PendienteRegistroDosFactores pendiente = pendienteDe(sesion).orElseThrow();

        MvcResult resultado = enviar(sesion, cuerpoCliente())
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.id").value("id-cliente-1"))
                .andExpect(jsonPath("$.email").value(EMAIL))
                .andExpect(jsonPath("$.nombre").value("Ana"))
                .andExpect(jsonPath("$.mensaje").isNotEmpty())
                .andExpect(jsonPath("$.codigosRecuperacion[0]").value(CODIGOS.get(0)))
                .andExpect(jsonPath("$.codigosRecuperacion[1]").value(CODIGOS.get(1)))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.contrasena").doesNotExist())
                .andReturn();

        // El servicio recibe la solicitud con su codigo, el pendiente de la sesion y el contexto de la peticion
        verify(servicioRegistro).registrarCliente(argThat(solicitud -> CODIGO.equals(solicitud.codigoDosFactores())
                && EMAIL.equals(solicitud.email())), eq(pendiente), eq(CONTEXTO));
        assertThat(cuerpoDe(resultado)).doesNotContain(ConstructorSolicitudCliente.CONTRASENA_VALIDA)
                .doesNotContain("\"" + CODIGO + "\"");
        // Un secreto pendiente sirve para un solo registro
        assertThat(pendienteDe(sesion)).isEmpty();
    }

    @Test
    void registrarVendedor_conSecretoPendiente_devuelve201ConLosCodigosDeRecuperacion() throws Exception { // CP-2FA-485
        MockHttpSession sesion = sesionConPendiente();
        PendienteRegistroDosFactores pendiente = pendienteDe(sesion).orElseThrow();

        enviar(sesion, cuerpoVendedor())
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.codigosRecuperacion", Matchers.hasSize(2)));

        verify(servicioRegistro).registrarVendedor(argThat(solicitud -> CODIGO.equals(solicitud.codigoDosFactores())),
                eq(pendiente), eq(CONTEXTO));
        assertThat(pendienteDe(sesion)).isEmpty();
    }

    @Test
    void registrar_sinToken_csrf_devuelve403SinLlamarAlServicioNiTocarElPendiente() throws Exception { // CP-2FA-486
        MockHttpSession sesion = sesionConPendiente();

        mockMvc.perform(registro(sesion, cuerpoCliente(), false)).andExpect(status().isForbidden());

        verifyNoInteractions(servicioRegistro);
        assertThat(pendienteDe(sesion)).isPresent();
    }

    @Test
    void registrar_sinSesionOConUnaSesionSinPendiente_llamaAlServicioConPendienteNulo() throws Exception { // CP-2FA-487
        // El servicio es quien responde ACTIVACION_NO_VIGENTE; el controlador no decide por el
        enviar(null, cuerpoCliente()).andExpect(status().isCreated());
        enviar(new MockHttpSession(), cuerpoCliente()).andExpect(status().isCreated());

        verify(servicioRegistro, times(2)).registrarCliente(any(), isNull(), eq(CONTEXTO));
    }

    @Test
    void registrar_elSecretoPendienteDeOtraSesionNoSeUsa() throws Exception { // CP-2FA-488
        sesionConPendiente(); // la de otra persona

        enviar(new MockHttpSession(), cuerpoCliente()).andExpect(status().isCreated());

        verify(servicioRegistro).registrarCliente(any(), isNull(), eq(CONTEXTO));
    }

    // ------------------------------------------------------------------ codigo incorrecto y agotamiento

    @Test
    void registrar_conCodigoIncorrecto_devuelve400YSumaUnFallo() throws Exception { // CP-2FA-489
        MockHttpSession sesion = sesionConPendiente();
        when(servicioRegistro.registrarCliente(any(), any(), any())).thenThrow(errorDeCodigo(CodigoError.CODIGO_INCORRECTO));

        enviar(sesion, cuerpoCliente())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores.codigoDosFactores[0]").value("CODIGO_INCORRECTO"));

        assertThat(pendienteDe(sesion)).hasValueSatisfying(pendiente -> assertThat(pendiente.intentosFallidos()).isEqualTo(1));
        verify(auditoria, never()).registrarRegistroDosFactoresAgotado(any());
    }

    @Test
    void registrar_alAgotarLosCodigosFallidos_descartaElPendienteYResponde_activacionNoVigente() throws Exception { // CP-2FA-490
        MockHttpSession sesion = sesionConPendiente();
        when(servicioRegistro.registrarCliente(any(), any(), any())).thenThrow(errorDeCodigo(CodigoError.CODIGO_INCORRECTO));

        for (int intento = 1; intento <= 4; intento++) {
            enviar(sesion, cuerpoCliente()).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errores.codigoDosFactores[0]").value("CODIGO_INCORRECTO"));
        }
        assertThat(pendienteDe(sesion)).hasValueSatisfying(pendiente -> assertThat(pendiente.intentosFallidos()).isEqualTo(4));
        verify(auditoria, never()).registrarRegistroDosFactoresAgotado(any());

        // El quinto agota el maximo: ya no hay nada que reintentar con ese QR
        enviar(sesion, cuerpoCliente()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores.codigoDosFactores[0]").value("ACTIVACION_NO_VIGENTE"))
                .andExpect(jsonPath("$.errores.codigoDosFactores", Matchers.hasSize(1)));

        assertThat(pendienteDe(sesion)).isEmpty();
        verify(auditoria).registrarRegistroDosFactoresAgotado(CONTEXTO);
    }

    @Test
    void registrar_conActivacionNoVigente_devuelve400YDescartaElPendiente() throws Exception { // CP-2FA-491
        MockHttpSession sesion = sesionConPendiente();
        when(servicioRegistro.registrarCliente(any(), any(), any())).thenThrow(errorDeCodigo(CodigoError.ACTIVACION_NO_VIGENTE));

        enviar(sesion, cuerpoCliente())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores.codigoDosFactores[0]").value("ACTIVACION_NO_VIGENTE"));

        assertThat(pendienteDe(sesion)).isEmpty();
        verify(auditoria, never()).registrarRegistroDosFactoresAgotado(any());
    }

    // ------------------------------------------------------------------ errores corregibles: no tocan el pendiente

    @Test
    void registrar_conErroresDeFormato_incluidoElCodigoObligatorio_noTocaElPendiente() throws Exception { // CP-2FA-492
        MockHttpSession sesion = sesionConPendiente();
        Map<String, Set<CodigoError>> errores = new LinkedHashMap<>();
        errores.put("nombre", Set.of(CodigoError.OBLIGATORIO));
        errores.put("codigoDosFactores", Set.of(CodigoError.OBLIGATORIO));
        when(servicioRegistro.registrarCliente(any(), any(), any())).thenThrow(new DatosRegistroInvalidosException(errores));

        enviar(sesion, cuerpoCliente())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores.nombre[0]").value("OBLIGATORIO"))
                .andExpect(jsonPath("$.errores.codigoDosFactores[0]").value("OBLIGATORIO"));

        assertThat(pendienteDe(sesion)).hasValueSatisfying(pendiente -> assertThat(pendiente.intentosFallidos()).isZero());
    }

    @Test
    void registrar_conUnErrorDeDominioDelCorreo_noTocaElPendiente() throws Exception { // CP-2FA-493
        MockHttpSession sesion = sesionConPendiente();
        when(servicioRegistro.registrarCliente(any(), any(), any())).thenThrow(
                new DatosRegistroInvalidosException(Map.of("email", Set.of(CodigoError.DOMINIO_EMAIL_INEXISTENTE))));

        enviar(sesion, cuerpoCliente()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores.email[0]").value("DOMINIO_EMAIL_INEXISTENTE"));

        assertThat(pendienteDe(sesion)).hasValueSatisfying(pendiente -> assertThat(pendiente.intentosFallidos()).isZero());
    }

    @Test
    void registrar_conElCorreoYaRegistrado_devuelve409GenericoYNoTocaElPendiente() throws Exception { // CP-2FA-494
        MockHttpSession sesion = sesionConPendiente();
        when(servicioRegistro.registrarCliente(any(), any(), any())).thenThrow(new RegistroNoCompletadoException());

        enviar(sesion, cuerpoCliente()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensaje").value(RegistroNoCompletadoException.MENSAJE_GENERICO));

        assertThat(pendienteDe(sesion)).hasValueSatisfying(pendiente -> assertThat(pendiente.intentosFallidos()).isZero());
    }

    @Test
    void registrar_conServicioNoDisponible_devuelve503YNoTocaElPendiente() throws Exception { // CP-2FA-495
        MockHttpSession sesion = sesionConPendiente();
        when(servicioRegistro.registrarCliente(any(), any(), any())).thenThrow(new ServicioNoDisponibleException(null));

        enviar(sesion, cuerpoCliente()).andExpect(status().isServiceUnavailable());

        assertThat(pendienteDe(sesion)).hasValueSatisfying(pendiente -> assertThat(pendiente.intentosFallidos()).isZero());
    }

    @Test
    void registrar_conUnFalloInesperado_devuelve500GenericoYNoTocaElPendiente() throws Exception { // CP-2FA-496
        MockHttpSession sesion = sesionConPendiente();
        when(servicioRegistro.registrarCliente(any(), any(), any())).thenThrow(new IllegalStateException("detalle interno"));

        MvcResult resultado = enviar(sesion, cuerpoCliente()).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.correlationId").isNotEmpty()).andReturn();

        assertThat(cuerpoDe(resultado)).doesNotContain("detalle interno");
        assertThat(pendienteDe(sesion)).hasValueSatisfying(pendiente -> assertThat(pendiente.intentosFallidos()).isZero());
    }

    // ------------------------------------------------------------------ cuerpo

    @Test
    void registrar_conUnCodigoQueNoEsTexto_devuelve400SinLlamarAlServicio() throws Exception { // CP-2FA-497
        MockHttpSession sesion = sesionConPendiente();
        Map<String, Object> cuerpo = objectMapper.convertValue(ConstructorSolicitudCliente.unaSolicitudValida().construir(),
                new tools.jackson.core.type.TypeReference<Map<String, Object>>() { });

        for (Object valor : List.of(List.of("123456"), Map.of("codigo", "123456"))) {
            Map<String, Object> copia = new LinkedHashMap<>(cuerpo);
            copia.put("codigoDosFactores", valor);
            enviar(sesion, objectMapper.writeValueAsString(copia)).andExpect(status().isBadRequest());
        }

        verifyNoInteractions(servicioRegistro);
        assertThat(pendienteDe(sesion)).hasValueSatisfying(pendiente -> assertThat(pendiente.intentosFallidos()).isZero());
    }

    @Test
    void registrar_unCampoDesconocidoSigueSiendoUn400() throws Exception { // CP-2FA-498
        Map<String, Object> cuerpo = objectMapper.convertValue(ConstructorSolicitudCliente.unaSolicitudValida().construir(),
                new tools.jackson.core.type.TypeReference<Map<String, Object>>() { });
        cuerpo.put("secretoDosFactores", "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");

        enviar(sesionConPendiente(), objectMapper.writeValueAsString(cuerpo)).andExpect(status().isBadRequest());

        verifyNoInteractions(servicioRegistro);
    }
}
