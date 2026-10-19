package com.esibuy.esibuy_backend.integracion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.esibuy.esibuy_backend.servicio.DiccionarioContrasenasProhibidas;
import com.esibuy.esibuy_backend.servicio.EnviadorCorreo;
import com.esibuy.esibuy_backend.servicio.GeneradorTokens;
import com.esibuy.esibuy_backend.servicio.MensajeCorreo;
import com.esibuy.esibuy_backend.servicio.ServicioTokensRecuperacion;
import com.esibuy.esibuy_backend.servicio.TokenEmitido;
import com.esibuy.esibuy_backend.util.CapturadorLogs;
import com.esibuy.esibuy_backend.util.RelojMutable;
import com.mongodb.client.MongoCollection;

import jakarta.servlet.http.HttpSession;

/**
 * Restablecimiento de la contrasena de punta a punta: HTTP, seguridad, servicios, MongoDB real, Argon2id real, sesiones
 * reales y reloj manual (HdU recuperacion de contrasena, fase 4). Casos: CP-RST-70 a CP-RST-81.
 *
 * Lo mas importante que fijan, visto desde fuera:
 *  - Tras restablecer, la contrasena antigua deja de valer, la nueva entra, y TODAS las sesiones que el usuario tenia
 *    abiertas devuelven 401, mientras las de otros usuarios siguen funcionando.
 *  - El enlace se gasta una sola vez, caduca a los 15 minutos, y una contrasena rechazada por la politica no lo gasta.
 *  - Todas las formas de enlace malo (desconocido, mal formado, vacio, caducado, ya usado) dan exactamente la misma
 *    respuesta HTTP.
 *  - En la base de datos solo queda el hash de la contrasena, y la marca contrasenaCambiadaEn.
 *  - El limite de intentos por IP da 429 y no afecta a otras IP.
 *
 * Cada test usa sus propios correos e IP, porque los contadores viven en el contexto de Spring compartido. Se evita la
 * consulta real a Pwned Passwords sustituyendo el diccionario.
 *
 * Terminada: ya no lleva la etiqueta pendiente-recuperacion y entra en la regresion.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class RestablecimientoIntegracionMongoTest extends IntegracionLoginBase {

    private static final String RUTA = "/api/auth/recuperacion/restablecer";
    private static final String RUTA_SOLICITUD = "/api/auth/recuperacion";
    private static final String RUTA_LOGIN = "/api/auth/login";
    private static final String RUTA_ME = "/api/auth/me";
    private static final String ANTIGUA = "Clave-Antigua-De-Prueba-7!";
    private static final String NUEVA = "Nueva-Clave-De-Prueba-42!";
    private static final Pattern ENLACE = Pattern.compile("restablecer-contrasena#token=([A-Za-z0-9_-]{43})");

    private static final RelojMutable RELOJ =
            new RelojMutable(Instant.parse("2026-10-19T10:00:00Z"), ZoneId.of("Europe/Madrid"));
    private static final AtomicInteger CONTADOR = new AtomicInteger();

    static class GrabadorDeCorreos implements EnviadorCorreo {
        final List<MensajeCorreo> recibidos = new CopyOnWriteArrayList<>();

        @Override
        public void enviar(MensajeCorreo mensaje) {
            recibidos.add(mensaje);
        }
    }

    @TestConfiguration
    static class ConfiguracionDePrueba {
        @Bean
        @Primary
        Clock relojDePrueba() {
            return RELOJ;
        }

        @Bean
        @Primary
        GrabadorDeCorreos grabadorDeCorreos() {
            return new GrabadorDeCorreos();
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private MongoTemplate mongo;
    @Autowired
    private PasswordEncoder codificador;
    @Autowired
    private ServicioTokensRecuperacion servicioTokens;
    @Autowired
    private GrabadorDeCorreos correo;

    @MockitoBean
    private DiccionarioContrasenasProhibidas diccionario;

    private MongoCollection<Document> usuarios;
    private MongoCollection<Document> tokens;

    @BeforeEach
    void prepararColecciones() {
        usuarios = mongo.getCollection("usuarios");
        tokens = mongo.getCollection("tokens_recuperacion");
        usuarios.deleteMany(new Document());
        tokens.deleteMany(new Document());
        correo.recibidos.clear();
    }

    // ------------------------------------------------------------------ utilidades

    private static String correoNuevo() {
        return "usuario" + CONTADOR.incrementAndGet() + "@ejemplo.es";
    }

    private static String ipNueva() {
        int n = CONTADOR.incrementAndGet();
        return "10.8." + (n / 250) + "." + (n % 250 + 1);
    }

    private static RequestPostProcessor desdeLaIp(String ip) {
        return peticion -> {
            peticion.setRemoteAddr(ip);
            return peticion;
        };
    }

    private ObjectId crearUsuario(String email, String estado) {
        ObjectId id = new ObjectId();
        usuarios.insertOne(new Document("_id", id)
                .append("email", email)
                .append("password", codificador.encode(ANTIGUA))
                .append("estado", estado)
                .append("rol", List.of("CLIENTE"))
                .append("perfil", new Document("nombre", "Ana").append("apellidos", "Garcia")));
        return id;
    }

    private Document usuario(ObjectId id) {
        return usuarios.find(new Document("_id", id)).first();
    }

    private MvcResult login(String email, String contrasena) throws Exception {
        return mockMvc.perform(post(RUTA_LOGIN).with(csrf()).with(desdeLaIp(ipNueva()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"contrasena\":\"" + contrasena + "\"}")).andReturn();
    }

    private MockHttpSession iniciarSesion(String email, String contrasena) throws Exception {
        MvcResult resultado = login(email, contrasena);
        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        return (MockHttpSession) resultado.getRequest().getSession(false);
    }

    private int estadoDeMe(MockHttpSession sesion) throws Exception {
        return mockMvc.perform(get(RUTA_ME).session(sesion)).andReturn().getResponse().getStatus();
    }

    private MvcResult restablecer(String token, String contrasena, String ip) throws Exception {
        return mockMvc.perform(post(RUTA).with(csrf()).with(desdeLaIp(ip)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\",\"contrasena\":\"" + contrasena + "\"}")).andReturn();
    }

    private MvcResult restablecer(String token, String contrasena) throws Exception {
        return restablecer(token, contrasena, ipNueva());
    }

    private static String cuerpoDe(MvcResult resultado) throws Exception {
        return resultado.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static Map<String, Object> huellaDeLaRespuesta(MvcResult resultado) throws Exception {
        Map<String, Object> huella = new TreeMap<>();
        huella.put("estado", resultado.getResponse().getStatus());
        huella.put("tipo", resultado.getResponse().getContentType());
        huella.put("cuerpo", cuerpoDe(resultado));
        for (String nombre : resultado.getResponse().getHeaderNames()) {
            if (!nombre.equalsIgnoreCase("X-Correlation-Id") && !nombre.equalsIgnoreCase("Date")) {
                huella.put("cabecera:" + nombre, resultado.getResponse().getHeaders(nombre));
            }
        }
        return huella;
    }

    // ------------------------------------------------------------------ CP-RST-70: camino completo

    @Test
    void restablecer_conUnTokenValido_cambiaLaContrasenaEInvalidaTodasLasSesionesDelUsuario() throws Exception {
        // CP-RST-70
        // Given: dos sesiones abiertas del usuario y una de otra persona
        String email = correoNuevo();
        ObjectId id = crearUsuario(email, "ACTIVO");
        String otroEmail = correoNuevo();
        crearUsuario(otroEmail, "ACTIVO");
        MockHttpSession sesionA = iniciarSesion(email, ANTIGUA);
        MockHttpSession sesionB = iniciarSesion(email, ANTIGUA);
        MockHttpSession sesionDeOtro = iniciarSesion(otroEmail, ANTIGUA);
        assertThat(estadoDeMe(sesionA)).isEqualTo(200);
        assertThat(estadoDeMe(sesionB)).isEqualTo(200);
        String hashAntiguo = usuario(id).getString("password");
        TokenEmitido emitido = servicioTokens.emitir(id.toHexString());

        // When
        MvcResult resultado = restablecer(emitido.token(), NUEVA);

        // Then: 200 y, en la base de datos, un hash nuevo (Argon2id), la marca de cambio, y nada en claro
        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        Document guardado = usuario(id);
        assertThat(guardado.getString("password")).startsWith("$argon2id").isNotEqualTo(hashAntiguo);
        assertThat(guardado.getDate("contrasenaCambiadaEn")).isEqualTo(Date.from(RELOJ.instant()));
        assertThat(guardado.toJson()).doesNotContain(NUEVA, ANTIGUA);
        // el token queda usado y el usuario no tiene ninguno vigente
        assertThat(tokens.countDocuments(new Document("estado", "USADO"))).isEqualTo(1);
        assertThat(tokens.countDocuments(new Document("estado", "VIGENTE"))).isZero();

        // Then: sus dos sesiones antiguas ya no valen, pero la de otro usuario si
        assertThat(estadoDeMe(sesionA)).isEqualTo(401);
        assertThat(estadoDeMe(sesionB)).isEqualTo(401);
        assertThat(estadoDeMe(sesionDeOtro)).isEqualTo(200);

        // Then: la contrasena antigua ya no entra; con la nueva si, y la sesion nueva vale
        assertThat(login(email, ANTIGUA).getResponse().getStatus()).isEqualTo(401);
        RELOJ.avanzar(Duration.ofSeconds(2));
        MockHttpSession sesionNueva = iniciarSesion(email, NUEVA);
        assertThat(estadoDeMe(sesionNueva)).isEqualTo(200);
    }

    // ------------------------------------------------------------------ CP-RST-71 / 72 / 73 / 74

    @Test
    void restablecer_elMismoTokenDosVeces_laSegundaFallaYNoCambiaNada() throws Exception { // CP-RST-71
        // Given
        String email = correoNuevo();
        ObjectId id = crearUsuario(email, "ACTIVO");
        TokenEmitido emitido = servicioTokens.emitir(id.toHexString());
        assertThat(restablecer(emitido.token(), NUEVA).getResponse().getStatus()).isEqualTo(200);
        String hashTrasElPrimero = usuario(id).getString("password");

        // When: se reutiliza el enlace con otra contrasena distinta
        MvcResult segunda = restablecer(emitido.token(), "Otra-Clave-Distinta-99!");

        // Then
        assertThat(segunda.getResponse().getStatus()).isEqualTo(400);
        assertThat(cuerpoDe(segunda)).contains("\"token\":[\"ENLACE_INVALIDO\"]");
        assertThat(usuario(id).getString("password")).isEqualTo(hashTrasElPrimero);
    }

    @Test
    void restablecer_tokenCaducado_falla400YNoCambiaNada() throws Exception { // CP-RST-72
        String email = correoNuevo();
        ObjectId id = crearUsuario(email, "ACTIVO");
        TokenEmitido emitido = servicioTokens.emitir(id.toHexString());
        String hashAntes = usuario(id).getString("password");

        RELOJ.avanzar(Duration.ofMinutes(15));
        MvcResult resultado = restablecer(emitido.token(), NUEVA);

        // A los 15 minutos exactos ya no vale
        assertThat(resultado.getResponse().getStatus()).isEqualTo(400);
        assertThat(cuerpoDe(resultado)).contains("\"token\":[\"ENLACE_INVALIDO\"]");
        assertThat(usuario(id).getString("password")).isEqualTo(hashAntes);
        assertThat(usuario(id)).doesNotContainKey("contrasenaCambiadaEn");
    }

    @Test
    void restablecer_contrasenaQueIncumpleLaPolitica_falla400SinGastarElToken() throws Exception { // CP-RST-73
        // Given
        String email = correoNuevo();
        ObjectId id = crearUsuario(email, "ACTIVO");
        TokenEmitido emitido = servicioTokens.emitir(id.toHexString());
        String hashAntes = usuario(id).getString("password");

        // When: primero una contrasena sin numero, sin especial y corta, y despues una con datos personales
        MvcResult debil = restablecer(emitido.token(), "corta");
        MvcResult conNombre = restablecer(emitido.token(), "Mi-Garcia-Clave-42!");

        // Then: todos los errores a la vez, nada cambia y el enlace sigue valiendo
        assertThat(debil.getResponse().getStatus()).isEqualTo(400);
        assertThat(cuerpoDe(debil)).contains("\"contrasena\"", "CONTRASENA_CORTA", "CONTRASENA_SIN_NUMERO",
                "CONTRASENA_SIN_CARACTER_ESPECIAL", "CONTRASENA_SIN_MAYUSCULA");
        assertThat(cuerpoDe(conNombre)).contains("CONTRASENA_CON_DATOS_PERSONALES");
        assertThat(usuario(id).getString("password")).isEqualTo(hashAntes);
        assertThat(tokens.countDocuments(new Document("estado", "VIGENTE"))).isEqualTo(1);

        // y con una buena contrasena, el mismo enlace funciona
        assertThat(restablecer(emitido.token(), NUEVA).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void restablecer_cuentaQueSeDesactivoDespuesDeEmitirElToken_falla400ComoCualquierEnlaceMalo() throws Exception {
        // CP-RST-74
        String email = correoNuevo();
        ObjectId id = crearUsuario(email, "ACTIVO");
        TokenEmitido emitido = servicioTokens.emitir(id.toHexString());
        usuarios.updateOne(new Document("_id", id), new Document("$set", new Document("estado", "DESACTIVADO")));
        String hashAntes = usuario(id).getString("password");

        MvcResult resultado = restablecer(emitido.token(), NUEVA);

        assertThat(resultado.getResponse().getStatus()).isEqualTo(400);
        assertThat(cuerpoDe(resultado)).contains("\"token\":[\"ENLACE_INVALIDO\"]").doesNotContain("DESACTIVADO");
        assertThat(usuario(id).getString("password")).isEqualTo(hashAntes);
        // y el enlace ya no se puede usar aunque la cuenta se reactive
        assertThat(tokens.countDocuments(new Document("estado", "VIGENTE"))).isZero();
    }

    // ------------------------------------------------------------------ CP-RST-75

    @Test
    void restablecer_todasLasFormasDeEnlaceMalo_danExactamenteLaMismaRespuestaHttp() throws Exception { // CP-RST-75
        // Given: un token usado y uno caducado, de dos cuentas distintas
        ObjectId usadoId = crearUsuario(correoNuevo(), "ACTIVO");
        TokenEmitido usado = servicioTokens.emitir(usadoId.toHexString());
        assertThat(restablecer(usado.token(), NUEVA).getResponse().getStatus()).isEqualTo(200);
        ObjectId caducadoId = crearUsuario(correoNuevo(), "ACTIVO");
        TokenEmitido caducado = servicioTokens.emitir(caducadoId.toHexString());
        RELOJ.avanzar(Duration.ofMinutes(16));

        // When
        Map<String, Object> desconocido = huellaDeLaRespuesta(restablecer(new GeneradorTokens().nuevoToken(), NUEVA));
        Map<String, Object> malFormado = huellaDeLaRespuesta(restablecer("xyz", NUEVA));
        Map<String, Object> vacio = huellaDeLaRespuesta(restablecer("", NUEVA));
        Map<String, Object> yaUsado = huellaDeLaRespuesta(restablecer(usado.token(), NUEVA));
        Map<String, Object> caducadoResp = huellaDeLaRespuesta(restablecer(caducado.token(), NUEVA));

        // Then: un atacante no puede distinguir por que no vale
        assertThat(desconocido.get("estado")).isEqualTo(400);
        assertThat(malFormado).isEqualTo(desconocido);
        assertThat(vacio).isEqualTo(desconocido);
        assertThat(yaUsado).isEqualTo(desconocido);
        assertThat(caducadoResp).isEqualTo(desconocido);
    }

    // ------------------------------------------------------------------ CP-RST-76

    @Test
    void recuperarYRestablecer_flujoCompletoConElCorreo_permiteEntrarConLaContrasenaNueva() throws Exception {
        // CP-RST-76
        // Given: una cuenta activa que ha olvidado su contrasena
        String email = correoNuevo();
        crearUsuario(email, "ACTIVO");

        // When: pide la recuperacion, recibe el correo y usa el enlace
        mockMvc.perform(post(RUTA_SOLICITUD).with(csrf()).with(desdeLaIp(ipNueva()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\"}"));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(correo.recibidos).hasSize(1));
        Matcher enlace = ENLACE.matcher(correo.recibidos.get(0).cuerpo());
        assertThat(enlace.find()).isTrue();
        assertThat(restablecer(enlace.group(1), NUEVA).getResponse().getStatus()).isEqualTo(200);

        // Then: entra con la contrasena nueva y no con la antigua
        RELOJ.avanzar(Duration.ofSeconds(2));
        assertThat(login(email, NUEVA).getResponse().getStatus()).isEqualTo(200);
        assertThat(login(email, ANTIGUA).getResponse().getStatus()).isEqualTo(401);
    }

    // ------------------------------------------------------------------ CP-RST-77

    @Test
    void restablecer_elIntentoOnceDeUnaMismaIp_da429YOtraIpNoSeVeAfectada() throws Exception { // CP-RST-77
        String ip = ipNueva();
        for (int i = 1; i <= 10; i++) {
            assertThat(restablecer(new GeneradorTokens().nuevoToken(), NUEVA, ip).getResponse().getStatus())
                    .as("intento %d", i).isEqualTo(400);
        }

        MvcResult limitado = restablecer(new GeneradorTokens().nuevoToken(), NUEVA, ip);

        assertThat(limitado.getResponse().getStatus()).isEqualTo(429);
        assertThat(cuerpoDe(limitado)).doesNotContain(ip, NUEVA);
        assertThat(restablecer(new GeneradorTokens().nuevoToken(), NUEVA, ipNueva()).getResponse().getStatus())
                .isEqualTo(400);
    }

    // ------------------------------------------------------------------ CP-RST-78 / 79

    @Test
    void restablecer_noIniciaSesion() throws Exception { // CP-RST-78
        ObjectId id = crearUsuario(correoNuevo(), "ACTIVO");
        TokenEmitido emitido = servicioTokens.emitir(id.toHexString());

        MvcResult resultado = restablecer(emitido.token(), NUEVA);

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        HttpSession sesion = resultado.getRequest().getSession(false);
        assertThat(sesion == null
                || sesion.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) == null)
                .isTrue();
    }

    @Test
    void restablecer_sinTokenCsrf_devuelve403YNoCambiaNada() throws Exception { // CP-RST-79
        ObjectId id = crearUsuario(correoNuevo(), "ACTIVO");
        TokenEmitido emitido = servicioTokens.emitir(id.toHexString());
        String hashAntes = usuario(id).getString("password");

        int estado = mockMvc.perform(post(RUTA).with(desdeLaIp(ipNueva())).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + emitido.token() + "\",\"contrasena\":\"" + NUEVA + "\"}"))
                .andReturn().getResponse().getStatus();

        assertThat(estado).isEqualTo(403);
        assertThat(usuario(id).getString("password")).isEqualTo(hashAntes);
        assertThat(servicioTokens.validar(emitido.token()).esValido()).isTrue();
    }

    // ------------------------------------------------------------------ CP-RST-80 / 81

    @Test
    void restablecer_variosFlujos_nadaEnElLogContieneLaContrasenaNiElToken() throws Exception { // CP-RST-80
        ObjectId id = crearUsuario(correoNuevo(), "ACTIVO");
        TokenEmitido emitido = servicioTokens.emitir(id.toHexString());

        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            restablecer(emitido.token(), "Mi-Garcia-Clave-42!");
            restablecer("token-malo", NUEVA);
            restablecer(emitido.token(), NUEVA);
            restablecer(emitido.token(), NUEVA); // reutilizacion

            // Solo los logs de la aplicacion (no las trazas internas de Spring o del driver, que se capturan a TRACE)
            List<String> textos = logs.eventos().stream().filter(e -> e.getLoggerName().startsWith("com.esibuy"))
                    .map(e -> e.getFormattedMessage() + " " + e.getMDCPropertyMap()
                            + (e.getThrowableProxy() == null ? "" : e.getThrowableProxy().getMessage()))
                    .toList();
            assertThat(textos).isNotEmpty().noneMatch(texto -> texto.contains(emitido.token())
                    || texto.contains(NUEVA) || texto.contains("Mi-Garcia-Clave-42!") || texto.contains("token-malo"));
            // y la reutilizacion si queda registrada como alerta
            assertThat(textos).anyMatch(texto -> texto.contains("TOKEN_RECUPERACION_REUTILIZADO"));
        }
    }

    @Test
    void sesion_deUnUsuarioQueYaNoExiste_dejaDeValer() throws Exception { // CP-RST-81
        // Efecto del mismo mecanismo: la comprobacion de la sesion falla cerrado si el usuario se ha borrado
        String email = correoNuevo();
        ObjectId id = crearUsuario(email, "ACTIVO");
        MockHttpSession sesion = iniciarSesion(email, ANTIGUA);
        assertThat(estadoDeMe(sesion)).isEqualTo(200);

        usuarios.deleteOne(new Document("_id", id));

        assertThat(estadoDeMe(sesion)).isEqualTo(401);
    }
}
