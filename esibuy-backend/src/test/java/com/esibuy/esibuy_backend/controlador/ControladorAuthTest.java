package com.esibuy.esibuy_backend.controlador;

import com.esibuy.esibuy_backend.configuracion.FiltroCorrelacionId;
import com.esibuy.esibuy_backend.dto.RespuestaRegistroDTO;
import com.esibuy.esibuy_backend.dto.TipoCuenta;
import com.esibuy.esibuy_backend.excepcion.CodigoError;
import com.esibuy.esibuy_backend.excepcion.DatosRegistroInvalidosException;
import com.esibuy.esibuy_backend.excepcion.RegistroNoCompletadoException;
import com.esibuy.esibuy_backend.excepcion.ServicioNoDisponibleException;
import com.esibuy.esibuy_backend.seguridad.ConfiguracionSeguridad;
import com.esibuy.esibuy_backend.servicio.ServicioRegistro;
import com.esibuy.esibuy_backend.util.CapturadorLogs;
import com.esibuy.esibuy_backend.util.ConstructorSolicitudCliente;
import com.esibuy.esibuy_backend.util.ConstructorSolicitudVendedor;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas del endpoint de registro con la cadena de seguridad REAL y el servicio mockeado.
 *
 * Decisiones que fijan estas pruebas (el equipo puede cambiarlas, pero deben cambiar a la vez aqui):
 *  - Un JSON con campos desconocidos se RECHAZA (400); asi no hay "mass assignment" posible.
 *  - Los errores de validacion se devuelven como {"errores": {"campo": ["CODIGO", ...]}}.
 *  - El duplicado responde 409 con un mensaje generico; los fallos inesperados 500 con correlationId.
 */
@WebMvcTest(ControladorAuth.class)
@Import(ConfiguracionSeguridad.class)
class ControladorAuthTest {

    // Un unico endpoint: el campo tipoCuenta del cuerpo decide si es cliente o vendedor
    private static final String RUTA_REGISTRO = "/api/auth/registro";

    private static final RespuestaRegistroDTO RESPUESTA_CLIENTE = new RespuestaRegistroDTO(
            "id-cliente-1", ConstructorSolicitudCliente.EMAIL_POR_DEFECTO, "Ana",
            "Tu cuenta ha sido creada correctamente");
    private static final RespuestaRegistroDTO RESPUESTA_VENDEDOR = new RespuestaRegistroDTO(
            "id-vendedor-1", ConstructorSolicitudVendedor.EMAIL_POR_DEFECTO, "Luis",
            "Tu cuenta ha sido creada correctamente");

    @Autowired
    private WebApplicationContext contexto;
    @Autowired
    private ObjectMapper objectMapper;
    @MockitoBean
    private ServicioRegistro servicioRegistro;

    private MockMvc mockMvc;

    @BeforeEach
    void prepararMockMvcYRespuestasPorDefectoDelServicio() {
        mockMvc = MockMvcBuilders.webAppContextSetup(contexto)
                .addFilters(new FiltroCorrelacionId())
                .apply(springSecurity())
                .build();
        when(servicioRegistro.registrarCliente(any())).thenReturn(RESPUESTA_CLIENTE);
        when(servicioRegistro.registrarVendedor(any())).thenReturn(RESPUESTA_VENDEDOR);
    }

    // ------------------------------------------------------------------ utilidades

    private ResultActions enviar(String ruta, String cuerpoJson) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post(ruta)
                .contentType(MediaType.APPLICATION_JSON)
                .content(cuerpoJson));
    }

    private String cuerpoClienteValido() {
        return objectMapper.writeValueAsString(ConstructorSolicitudCliente.unaSolicitudValida().construir());
    }

    private String cuerpoVendedorValido() {
        return objectMapper.writeValueAsString(ConstructorSolicitudVendedor.unaSolicitudValida().construir());
    }

    private Map<String, Object> mapaClienteValido() {
        return objectMapper.convertValue(ConstructorSolicitudCliente.unaSolicitudValida().construir(),
                new TypeReference<Map<String, Object>>() { });
    }

    private Map<String, Object> mapaVendedorValido() {
        return objectMapper.convertValue(ConstructorSolicitudVendedor.unaSolicitudValida().construir(),
                new TypeReference<Map<String, Object>>() { });
    }

    private String conCampo(Map<String, Object> base, String clave, Object valor) {
        Map<String, Object> copia = new LinkedHashMap<>(base);
        copia.put(clave, valor);
        return objectMapper.writeValueAsString(copia);
    }

    private static String cuerpoDe(MvcResult resultado) throws Exception {
        return resultado.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ CP-CTR-01

    @Test
    void registrarCliente_peticionValida_devuelve201ConConfirmacionSinHashNiContrasena() throws Exception { // CP-CTR-01
        // Given
        String cuerpo = cuerpoClienteValido();

        // When
        ResultActions resultado = enviar(RUTA_REGISTRO, cuerpo);

        // Then
        resultado.andExpect(status().isCreated())
                .andExpect(jsonPath("$.mensaje").isNotEmpty())
                .andExpect(jsonPath("$.email").value(ConstructorSolicitudCliente.EMAIL_POR_DEFECTO))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.contrasena").doesNotExist());
    }

    @Test
    void registrarVendedor_peticionValida_devuelve201ConConfirmacionSinHashNiContrasena() throws Exception { // CP-CTR-01
        // Given
        String cuerpo = cuerpoVendedorValido();

        // When
        ResultActions resultado = enviar(RUTA_REGISTRO, cuerpo);

        // Then
        resultado.andExpect(status().isCreated())
                .andExpect(jsonPath("$.mensaje").isNotEmpty())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.contrasena").doesNotExist());
    }

    // ------------------------------------------------------------------ CP-CTR-02, 03, 04

    @Test
    void registrarCliente_variosCamposInvalidos_devuelve400ConTodosLosErroresPorCampo() throws Exception { // CP-CTR-02
        // Given
        Map<String, Set<CodigoError>> errores = new LinkedHashMap<>();
        errores.put("nombre", Set.of(CodigoError.OBLIGATORIO));
        errores.put("email", Set.of(CodigoError.FORMATO_INVALIDO));
        errores.put("telefono", Set.of(CodigoError.TELEFONO_INVALIDO));
        when(servicioRegistro.registrarCliente(any())).thenThrow(new DatosRegistroInvalidosException(errores));

        // When
        ResultActions resultado = enviar(RUTA_REGISTRO, cuerpoClienteValido());

        // Then
        resultado.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores.nombre[0]").value("OBLIGATORIO"))
                .andExpect(jsonPath("$.errores.email[0]").value("FORMATO_INVALIDO"))
                .andExpect(jsonPath("$.errores.telefono[0]").value("TELEFONO_INVALIDO"));
    }

    @Test
    void registrarCliente_registroNoCompletado_devuelve409ConMensajeGenericoSinDatosDelUsuario() throws Exception { // CP-CTR-03
        // Given
        when(servicioRegistro.registrarCliente(any())).thenThrow(new RegistroNoCompletadoException());

        // When
        MvcResult resultado = enviar(RUTA_REGISTRO, cuerpoClienteValido())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensaje").value(RegistroNoCompletadoException.MENSAJE_GENERICO))
                .andReturn();

        // Then
        assertThat(cuerpoDe(resultado))
                .doesNotContain(ConstructorSolicitudCliente.EMAIL_POR_DEFECTO)
                .doesNotContainIgnoringCase("ya registrado");
    }

    @Test
    void registrarVendedor_categoriaInexistente_devuelve400ConErrorEnLaCategoria() throws Exception { // CP-CTR-04
        // Given
        Map<String, Set<CodigoError>> errores =
                Map.of("categoriaPrincipalId", Set.of(CodigoError.CATEGORIA_INEXISTENTE));
        when(servicioRegistro.registrarVendedor(any())).thenThrow(new DatosRegistroInvalidosException(errores));

        // When
        ResultActions resultado = enviar(RUTA_REGISTRO, cuerpoVendedorValido());

        // Then
        resultado.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores.categoriaPrincipalId[0]").value("CATEGORIA_INEXISTENTE"));
    }

    @Test
    void registrarCliente_dominioDeEmailInvalido_devuelve400ConErrorEnElEmail() throws Exception { // CP-CTR-04
        // Given
        Map<String, Set<CodigoError>> errores = Map.of("email", Set.of(CodigoError.DOMINIO_EMAIL_INEXISTENTE));
        when(servicioRegistro.registrarCliente(any())).thenThrow(new DatosRegistroInvalidosException(errores));

        // When
        ResultActions resultado = enviar(RUTA_REGISTRO, cuerpoClienteValido());

        // Then
        resultado.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores.email[0]").value("DOMINIO_EMAIL_INEXISTENTE"));
    }

    @Test
    void registrarCliente_servicioExternoNoDisponible_devuelve503ConMensajeGenerico() throws Exception { // CP-CTR-04
        // Given
        when(servicioRegistro.registrarCliente(any()))
                .thenThrow(new ServicioNoDisponibleException(new IllegalStateException("timeout DNS interno")));

        // When
        MvcResult resultado = enviar(RUTA_REGISTRO, cuerpoClienteValido())
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.mensaje").isNotEmpty())
                .andReturn();

        // Then
        assertThat(cuerpoDe(resultado)).doesNotContain("DNS");
    }

    // ------------------------------------------------------------------ CP-CTR-05 y CP-SEG-10

    @Test
    void registrarCliente_excepcionInesperada_devuelve500GenericoConCorrelationIdYDetalleSoloEnElLog() throws Exception { // CP-CTR-05
        // Given
        String detalleInterno = "Fallo interno al escribir en la coleccion users";
        when(servicioRegistro.registrarCliente(any())).thenThrow(new IllegalStateException(detalleInterno));

        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            // When
            MvcResult resultado = enviar(RUTA_REGISTRO, cuerpoClienteValido())
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.mensaje").isNotEmpty())
                    .andExpect(jsonPath("$.correlationId").isNotEmpty())
                    .andReturn();

            // Then
            String cuerpo = cuerpoDe(resultado);
            String correlationId = JsonPath.read(cuerpo, "$.correlationId");
            assertThat(cuerpo).doesNotContain(detalleInterno).doesNotContain("users");

            boolean logConDetalleYMismoCorrelationId = logs.eventos().stream().anyMatch(evento ->
                    evento.getThrowableProxy() != null
                            && evento.getThrowableProxy().getMessage().contains(detalleInterno)
                            && correlationId.equals(evento.getMDCPropertyMap().get("correlationId")));
            assertThat(logConDetalleYMismoCorrelationId).isTrue();
        }
    }

    @Test
    void registrarCliente_errorDeBaseDeDatos_laRespuestaNoFiltraDetallesInternos() throws Exception { // CP-SEG-10
        // Given
        when(servicioRegistro.registrarCliente(any())).thenThrow(new RuntimeException(
                "E11000 duplicate key error collection: ESIBuy.users index: email_1 (MongoDB 7.0.4)"));

        // When
        MvcResult resultado = enviar(RUTA_REGISTRO, cuerpoClienteValido())
                .andExpect(status().isInternalServerError())
                .andReturn();

        // Then
        assertThat(cuerpoDe(resultado))
                .doesNotContain("E11000")
                .doesNotContain("ESIBuy")
                .doesNotContain("email_1")
                .doesNotContain("Mongo")
                .doesNotContain("7.0.4")
                .doesNotContain("com.esibuy")
                .doesNotContain("org.springframework");
    }

    @Test
    void registrarCliente_cualquierRespuesta_incluyeLaCabeceraCorrelationId() throws Exception { // CP-REG-12
        // Given
        String cuerpo = cuerpoClienteValido();

        // When
        ResultActions resultado = enviar(RUTA_REGISTRO, cuerpo);

        // Then
        resultado.andExpect(header().exists("X-Correlation-Id"));
    }

    // ------------------------------------------------------------------ CP-SEG-01, 02, 06 (campos que no deben aceptarse)

    static Stream<Arguments> camposQueUnVisitanteNoPuedeEnviar() {
        return Stream.of(
                arguments("roles", List.of("ADMIN")),                      // CP-SEG-01
                arguments("roles", List.of("CUSTOMER", "PREMIUM")),        // CP-SEG-02 (mas de un rol)
                arguments("status", "BLOCKED"),                            // CP-SEG-01
                arguments("joinDate", "2026-01-01"),                       // CP-SEG-01
                arguments("passwordHash", "$argon2id$inventado"),          // CP-SEG-06
                arguments("campoInventado", "valor"));                     // CP-SEG-06
    }

    @ParameterizedTest(name = "campo no permitido en cliente: {0}={1}")
    @MethodSource("camposQueUnVisitanteNoPuedeEnviar")
    void registrarCliente_camposQueNoPuedeElegirElVisitante_rechazaYNoLlamaAlServicio( // CP-SEG-01, CP-SEG-02, CP-SEG-06
            String clave, Object valor) throws Exception {
        // Given
        String cuerpo = conCampo(mapaClienteValido(), clave, valor);

        // When
        ResultActions resultado = enviar(RUTA_REGISTRO, cuerpo);

        // Then
        resultado.andExpect(status().isBadRequest());
        verifyNoInteractions(servicioRegistro);
    }

    @ParameterizedTest(name = "campo no permitido en vendedor: {0}={1}")
    @MethodSource("camposQueUnVisitanteNoPuedeEnviar")
    void registrarVendedor_camposQueNoPuedeElegirElVisitante_rechazaYNoLlamaAlServicio( // CP-SEG-01, CP-SEG-02, CP-SEG-06
            String clave, Object valor) throws Exception {
        // Given
        String cuerpo = conCampo(mapaVendedorValido(), clave, valor);

        // When
        ResultActions resultado = enviar(RUTA_REGISTRO, cuerpo);

        // Then
        resultado.andExpect(status().isBadRequest());
        verifyNoInteractions(servicioRegistro);
    }

    @ParameterizedTest(name = "tipoCuenta no permitido: {0}")
    @ValueSource(strings = {"ADMIN", "ADMINISTRADOR", "SELLER", "NORMAL", "CUSTOMER_Y_PREMIUM"})
    void registrar_tipoDeCuentaNoPermitido_rechazaConErrorEnTipoCuentaYNoLlamaAlServicio(String tipo)
            throws Exception { // CP-SEG-02
        // Given
        String cuerpo = conCampo(mapaClienteValido(), "tipoCuenta", tipo);

        // When
        ResultActions resultado = enviar(RUTA_REGISTRO, cuerpo);

        // Then
        resultado.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores.tipoCuenta[0]").value("FORMATO_INVALIDO"));
        verifyNoInteractions(servicioRegistro);
    }

    @Test
    void registrar_tipoDeCuentaComoListaDeRoles_rechazaYNoLlamaAlServicio() throws Exception { // CP-SEG-02
        // Given
        String cuerpo = conCampo(mapaClienteValido(), "tipoCuenta", List.of("CLIENTE", "PREMIUM"));

        // When
        ResultActions resultado = enviar(RUTA_REGISTRO, cuerpo);

        // Then
        resultado.andExpect(status().isBadRequest());
        verifyNoInteractions(servicioRegistro);
    }

    // ------------------------------------------------------------------ tipoCuenta (endpoint unico de registro)

    @Test
    void registrar_sinTipoDeCuenta_devuelve400ConTipoCuentaObligatorio() throws Exception {
        // Given
        Map<String, Object> cuerpo = mapaClienteValido();
        cuerpo.remove("tipoCuenta");

        // When
        ResultActions resultado = enviar(RUTA_REGISTRO, objectMapper.writeValueAsString(cuerpo));

        // Then
        resultado.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores.tipoCuenta[0]").value("OBLIGATORIO"));
        verifyNoInteractions(servicioRegistro);
    }

    @ParameterizedTest(name = "tipoCuenta {0} -> registro de cliente")
    @ValueSource(strings = {"CLIENTE", "PREMIUM"})
    void registrar_tipoDeCuentaDeCliente_llamaAlRegistroDeClienteConEseTipo(String tipo) throws Exception {
        // Given
        String cuerpo = conCampo(mapaClienteValido(), "tipoCuenta", tipo);

        // When
        enviar(RUTA_REGISTRO, cuerpo).andExpect(status().isCreated());

        // Then
        verify(servicioRegistro).registrarCliente(argThat(solicitud -> solicitud.tipoCuenta() == TipoCuenta.valueOf(tipo)));
        verify(servicioRegistro, never()).registrarVendedor(any());
    }

    @Test
    void registrar_tipoDeCuentaVendedor_llamaAlRegistroDeVendedor() throws Exception {
        // Given
        String cuerpo = cuerpoVendedorValido();

        // When
        enviar(RUTA_REGISTRO, cuerpo).andExpect(status().isCreated());

        // Then
        verify(servicioRegistro).registrarVendedor(any());
        verify(servicioRegistro, never()).registrarCliente(any());
    }

    @Test
    void registrar_datosDeClienteDeclaradosComoVendedor_rechazaPorCamposQueNoSonDeVendedor() throws Exception {
        // Given: cuerpo de cliente (con fechaNacimiento) pero tipoCuenta VENDEDOR
        String cuerpo = conCampo(mapaClienteValido(), "tipoCuenta", "VENDEDOR");

        // When
        ResultActions resultado = enviar(RUTA_REGISTRO, cuerpo);

        // Then
        resultado.andExpect(status().isBadRequest());
        verifyNoInteractions(servicioRegistro);
    }

    @Test
    void registrar_datosDeVendedorDeclaradosComoCliente_rechazaPorCamposQueNoSonDeCliente() throws Exception {
        // Given: cuerpo de vendedor (con nombreComercial y categoria) pero tipoCuenta CLIENTE
        String cuerpo = conCampo(mapaVendedorValido(), "tipoCuenta", "CLIENTE");

        // When
        ResultActions resultado = enviar(RUTA_REGISTRO, cuerpo);

        // Then
        resultado.andExpect(status().isBadRequest());
        verifyNoInteractions(servicioRegistro);
    }

    // ------------------------------------------------------------------ CP-SEG-03 y 04 (inyeccion NoSQL en la estructura del JSON)

    static Stream<Arguments> estructurasJsonConOperadoresNoSql() {
        return Stream.of(
                arguments("email como objeto con $ne", "email", Collections.singletonMap("$ne", null)),
                arguments("nombre como objeto con $gt", "nombre", Map.of("$gt", "")),
                arguments("clave $where en la raiz", "$where", "sleep(5000)"),
                arguments("clave con punto en la raiz", "perfil.roles", "ADMIN"),
                arguments("objeto anidado con $where", "extra", Map.of("$where", "1==1")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("estructurasJsonConOperadoresNoSql")
    void registrarCliente_jsonConOperadoresNoSql_rechazaAntesDeLlegarAlServicio( // CP-SEG-03, CP-SEG-04
            String descripcion, String clave, Object valor) throws Exception {
        // Given
        String cuerpo = conCampo(mapaClienteValido(), clave, valor);

        // When
        ResultActions resultado = enviar(RUTA_REGISTRO, cuerpo);

        // Then
        resultado.andExpect(status().isBadRequest());
        verifyNoInteractions(servicioRegistro);
    }

    // ------------------------------------------------------------------ CP-REG-36 (fecha con formato incorrecto)

    @ParameterizedTest(name = "fecha con formato incorrecto: \"{0}\"")
    @ValueSource(strings = {"31/02/2000", "2000-13-01", "texto", "2000-02-30", ""})
    void registrarCliente_fechaDeNacimientoConFormatoIncorrecto_devuelve400LegibleConErrorEnLaFecha( // CP-REG-36
            String fechaIncorrecta) throws Exception {
        // Given
        String cuerpo = conCampo(mapaClienteValido(), "fechaNacimiento", fechaIncorrecta);

        // When
        MvcResult resultado = enviar(RUTA_REGISTRO, cuerpo)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores.fechaNacimiento").exists())
                .andReturn();

        // Then
        verifyNoInteractions(servicioRegistro);
        assertThat(cuerpoDe(resultado)).doesNotContain("Exception").doesNotContain("com.fasterxml").doesNotContain("tools.jackson");
    }

    // ------------------------------------------------------------------ CP-SEG-07 (endpoint publico)

    @ParameterizedTest(name = "registro publico de {0}")
    @ValueSource(strings = {"CLIENTE", "VENDEDOR"})
    void registrar_sinAutenticacionNiTokenCsrf_aceptaLaPeticion(String tipo) throws Exception { // CP-SEG-07
        // Given: visitante anonimo, sin sesion y sin token CSRF
        String cuerpo = tipo.equals("CLIENTE") ? cuerpoClienteValido() : cuerpoVendedorValido();

        // When
        ResultActions resultado = enviar(RUTA_REGISTRO, cuerpo);

        // Then
        resultado.andExpect(status().isCreated());
    }

    @ParameterizedTest(name = "registro publico de {0}")
    @ValueSource(strings = {"CLIENTE", "VENDEDOR"})
    void registrar_sinAutenticacionYConConflicto_respondeConflictoYNuncaNoAutorizadoNiProhibido(String tipo)
            throws Exception { // CP-SEG-07
        // Given
        when(servicioRegistro.registrarCliente(any())).thenThrow(new RegistroNoCompletadoException());
        when(servicioRegistro.registrarVendedor(any())).thenThrow(new RegistroNoCompletadoException());
        String cuerpo = tipo.equals("CLIENTE") ? cuerpoClienteValido() : cuerpoVendedorValido();

        // When
        MvcResult resultado = enviar(RUTA_REGISTRO, cuerpo).andReturn();

        // Then
        assertThat(resultado.getResponse().getStatus()).isEqualTo(409).isNotIn(401, 403);
    }

    // ------------------------------------------------------------------ CP-SEG-09 (peticiones malformadas)

    @Test
    void registrarCliente_sinCuerpo_devuelve400SinTrazas() throws Exception { // CP-SEG-09
        // Given: peticion JSON sin cuerpo

        // When
        MvcResult resultado = mockMvc.perform(MockMvcRequestBuilders.post(RUTA_REGISTRO)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andReturn();

        // Then
        assertThat(cuerpoDe(resultado))
                .doesNotContain("Exception").doesNotContain("com.esibuy").doesNotContain("org.springframework");
        verifyNoInteractions(servicioRegistro);
    }

    @Test
    void registrarCliente_contentTypeNoSoportado_devuelve415() throws Exception { // CP-SEG-09
        // Given
        String cuerpo = "nombre=Ana";

        // When
        ResultActions resultado = mockMvc.perform(MockMvcRequestBuilders.post(RUTA_REGISTRO)
                .contentType(MediaType.TEXT_PLAIN)
                .content(cuerpo));

        // Then
        resultado.andExpect(status().isUnsupportedMediaType());
        verifyNoInteractions(servicioRegistro);
    }

    @Test
    void registrarCliente_jsonMalFormado_devuelve400SinTrazas() throws Exception { // CP-SEG-09
        // Given
        String jsonRoto = "{\"nombre\": \"Ana\", \"email\": ";

        // When
        MvcResult resultado = enviar(RUTA_REGISTRO, jsonRoto)
                .andExpect(status().isBadRequest())
                .andReturn();

        // Then
        assertThat(cuerpoDe(resultado))
                .doesNotContain("Exception").doesNotContain("com.fasterxml").doesNotContain("tools.jackson").doesNotContain("org.springframework");
        verifyNoInteractions(servicioRegistro);
    }

    // ------------------------------------------------------------------ CP-SEG-11 (tamano del cuerpo)

    @Test
    void registrarCliente_cuerpoDesproporcionado_seRechazaAntesDeProcesar() throws Exception { // CP-SEG-11
        // Given: un nombre de un millon de caracteres
        String cuerpo = conCampo(mapaClienteValido(), "nombre", "a".repeat(1_000_000));

        // When
        MvcResult resultado = enviar(RUTA_REGISTRO, cuerpo).andReturn();

        // Then
        assertThat(resultado.getResponse().getStatus()).isIn(400, 413);
        verifyNoInteractions(servicioRegistro);
    }
}
