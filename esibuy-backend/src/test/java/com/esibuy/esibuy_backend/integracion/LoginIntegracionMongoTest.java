package com.esibuy.esibuy_backend.integracion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
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
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.bson.Document;
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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.esibuy.esibuy_backend.servicio.DiccionarioContrasenasProhibidas;
import com.esibuy.esibuy_backend.servicio.ValidadorDominioEmail;
import com.esibuy.esibuy_backend.util.CapturadorLogs;
import com.esibuy.esibuy_backend.util.ConstructorSolicitudCliente;
import com.esibuy.esibuy_backend.util.RelojMutable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import tools.jackson.databind.ObjectMapper;

/**
 * Login contra MongoDB real y Argon2id real. Necesita Docker en marcha.
 *
 * Orden TDD (plan de pruebas, sección 8): paso 7.
 * Casos: CP-LIN-01, CP-LIN-03, CP-LIN-05, CP-LIN-07, CP-LIN-08, CP-LIN-12.
 * Colaboradores: Solo ValidadorDominioEmail (para el registro previo) y un reloj mutable
 * (@TestConfiguration). Reutiliza el patrón de RegistroIntegracionMongoTest sin modificarlo.
 *
 * Notas de diseño de la clase:
 *  - Un registro deja la cuenta DESACTIVADO hasta que un administrador la activa (otro módulo del equipo), así
 *    que estas pruebas la activan escribiendo en la colección, como lo haría el panel de administración.
 *  - Los contadores del limitador viven en memoria y se comparten entre pruebas: cada prueba usa correos distintos
 *    y cada petición sale de una IP distinta, salvo donde se prueba lo contrario.
 *  - Los estados de las cuentas son los de la BBDD en español: ACTIVO, DESACTIVADO y BLOQUEADO.
 *
 * Desarrollada con el ciclo Red-Green-Refactor: primero el test (Arrange, Act, Assert), después el código
 * mínimo que lo hace pasar. Terminada: ya no lleva la etiqueta pendiente-login y entra en la regresión.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class LoginIntegracionMongoTest extends IntegracionLoginBase {

    private static final String RUTA_REGISTRO = "/api/auth/registro";
    private static final String RUTA_LOGIN = "/api/auth/login";
    private static final String CONTRASENA = ConstructorSolicitudCliente.CONTRASENA_VALIDA;
    private static final String CONTRASENA_INCORRECTA = "Otra-Contrasena-99";
    private static final String HASH_DE_PRUEBA = "$argon2id$hash-de-prueba";

    private static final RelojMutable RELOJ =
            new RelojMutable(Instant.parse("2026-10-06T10:00:00Z"), ZoneId.of("Europe/Madrid"));
    private static final AtomicInteger CONTADOR_DE_IPS = new AtomicInteger();

    /** Sustituye el reloj de la aplicación por uno que solo avanza cuando la prueba lo pide. */
    @TestConfiguration
    static class ConfiguracionDeReloj {
        @Bean
        @Primary
        Clock relojDePrueba() {
            return RELOJ;
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private MongoTemplate mongo;

    @MockitoBean
    private ValidadorDominioEmail validadorDominioEmail;
    @MockitoBean
    private DiccionarioContrasenasProhibidas diccionario;

    private MongoCollection<Document> usuarios;

    @BeforeEach
    void limpiarColeccionesYPrepararMocks() {
        usuarios = mongo.getCollection("usuarios");
        usuarios.deleteMany(new Document());
        when(validadorDominioEmail.tieneDominioValido(anyString())).thenReturn(true);
    }

    // ------------------------------------------------------------------ utilidades

    private static String ipNueva() {
        int numero = CONTADOR_DE_IPS.incrementAndGet();
        return "10.1." + (numero / 250) + "." + (numero % 250 + 1);
    }

    private static RequestPostProcessor desdeLaIp(String ip) {
        return peticion -> {
            peticion.setRemoteAddr(ip);
            return peticion;
        };
    }

    private ResultActions loginConCuerpo(String cuerpoJson) throws Exception {
        return mockMvc.perform(post(RUTA_LOGIN).with(csrf()).with(desdeLaIp(ipNueva()))
                .contentType(MediaType.APPLICATION_JSON).content(cuerpoJson));
    }

    private ResultActions login(String email, String contrasena) throws Exception {
        return loginConCuerpo("{\"email\":\"" + email + "\",\"contrasena\":\"" + contrasena + "\"}");
    }

    /** Registra un cliente con el endpoint real y lo activa, como haría el panel de administración. */
    private void registrarYActivar(String email) throws Exception {
        mockMvc.perform(post(RUTA_REGISTRO).contentType(MediaType.APPLICATION_JSON).content(
                objectMapper.writeValueAsString(ConstructorSolicitudCliente.unaSolicitudValida()
                        .conEmail(email).construir()))).andExpect(status().isCreated());
        cambiarEstado(email, "ACTIVO");
    }

    private void cambiarEstado(String email, String estado) {
        usuarios.updateOne(Filters.eq("email", email), Updates.set("estado", estado));
    }

    private static Document documentoCliente(String email, String hash) {
        return new Document("email", email)
                .append("password", hash)
                .append("estado", "ACTIVO")
                .append("rol", List.of("CLIENTE"))
                .append("perfil", new Document("nombre", "Ana").append("apellidos", "García"));
    }

    private static String cuerpoDe(MvcResult resultado) throws Exception {
        return resultado.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** Cabeceras de la respuesta sin las que cambian en cada petición (id de correlación y fecha). */
    private static Map<String, List<String>> cabecerasComparables(MockHttpServletResponse respuesta) {
        Map<String, List<String>> cabeceras = new TreeMap<>();
        for (String nombre : respuesta.getHeaderNames()) {
            String clave = nombre.toLowerCase();
            if (!clave.equals("x-correlation-id") && !clave.equals("date")) {
                cabeceras.put(clave, respuesta.getHeaders(nombre));
            }
        }
        return cabeceras;
    }

    // ------------------------------------------------------------------ CP-LIN-01

    @Test
    void login_tras_registrarseConLasMismasCredenciales_devuelve200YCreaLaSesion() throws Exception { // CP-LIN-01
        // Given: un cliente recién registrado; hasta que un administrador lo activa no puede entrar (403 pendiente)
        String email = "lin01@ejemplo.es";
        mockMvc.perform(post(RUTA_REGISTRO).contentType(MediaType.APPLICATION_JSON).content(
                objectMapper.writeValueAsString(ConstructorSolicitudCliente.unaSolicitudValida()
                        .conEmail(email).construir()))).andExpect(status().isCreated());
        // Con la contraseña correcta se le dice que su cuenta está pendiente; con una incorrecta no se revela nada
        login(email, CONTRASENA).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("CUENTA_PENDIENTE_DE_ACTIVACION"));
        login(email, CONTRASENA_INCORRECTA).andExpect(status().isUnauthorized());

        // When: se activa la cuenta y se inicia sesión con las mismas credenciales del registro
        cambiarEstado(email, "ACTIVO");
        MvcResult resultado = login(email, CONTRASENA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.rol").value("CLIENTE"))
                .andReturn();

        // Then: registro y login usan el mismo pepper y hay sesión
        assertThat(resultado.getRequest().getSession(false)).isNotNull();
    }

    // ------------------------------------------------------------------ CP-LIN-03

    @Test
    void login_contrasenaIncorrectaYCorreoNoRegistrado_respondenExactamenteIgual() throws Exception { // CP-LIN-03
        // Given
        String email = "lin03@ejemplo.es";
        registrarYActivar(email);
        String hashGuardado = usuarios.find(Filters.eq("email", email)).first().getString("password");

        // When
        MvcResult contrasenaMala = login(email, CONTRASENA_INCORRECTA).andExpect(status().isUnauthorized()).andReturn();
        MvcResult correoDesconocido = login("no.registrado@ejemplo.es", CONTRASENA)
                .andExpect(status().isUnauthorized()).andReturn();

        // Then: mismo cuerpo y mismas cabeceras (salvo correlationId y fecha), sin rastro del hash
        assertThat(cuerpoDe(contrasenaMala)).isEqualTo(cuerpoDe(correoDesconocido));
        assertThat(cabecerasComparables(contrasenaMala.getResponse()))
                .isEqualTo(cabecerasComparables(correoDesconocido.getResponse()));
        assertThat(cuerpoDe(contrasenaMala)).doesNotContain("$argon2id").doesNotContain(hashGuardado);
        assertThat(cuerpoDe(correoDesconocido)).doesNotContain("$argon2id");
    }

    // ------------------------------------------------------------------ CP-LIN-05

    @Test
    void login_usuarioConEstadoBloqueado_seRechazaIgualQueCredencialesIncorrectasYTrasActivarlo_entra() throws Exception { // CP-LIN-05
        // Given: una cuenta bloqueada por el administrador, con la contraseña correcta
        String email = "lin05@ejemplo.es";
        registrarYActivar(email);
        cambiarEstado(email, "BLOQUEADO");

        // When
        MvcResult cuentaBloqueada = login(email, CONTRASENA).andExpect(status().isUnauthorized()).andReturn();
        MvcResult credencialesIncorrectas = login("no.registrado@ejemplo.es", CONTRASENA)
                .andExpect(status().isUnauthorized()).andReturn();

        // Then: no se distingue de unas credenciales incorrectas
        assertThat(cuerpoDe(cuentaBloqueada)).isEqualTo(cuerpoDe(credencialesIncorrectas));

        // When / Then: tras activarla, entra
        cambiarEstado(email, "ACTIVO");
        login(email, CONTRASENA).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ CP-LIN-07

    @Test
    void login_inyeccionNoSqlConLaColeccionLlena_noAutenticaANadie() throws Exception { // CP-LIN-07
        // Given: una colección con muchos usuarios y uno real con una contraseña conocida
        for (int i = 0; i < 25; i++) {
            usuarios.insertOne(documentoCliente("relleno" + i + "@ejemplo.es", HASH_DE_PRUEBA));
        }
        registrarYActivar("lin07@ejemplo.es");

        // When / Then: ninguna de estas peticiones autentica a nadie (400 o 401) ni crea sesión
        List<String> cuerpos = List.of(
                "{\"email\":{\"$ne\":null},\"contrasena\":\"" + CONTRASENA + "\"}",
                "{\"email\":{\"$gt\":\"\"},\"contrasena\":\"" + CONTRASENA + "\"}",
                "{\"email\":\"{\\\"$gt\\\":\\\"\\\"}\",\"contrasena\":\"" + CONTRASENA + "\"}",
                "{\"email\":\"lin07@ejemplo.es\",\"contrasena\":{\"$ne\":\"\"}}");
        for (String cuerpo : cuerpos) {
            MvcResult resultado = loginConCuerpo(cuerpo).andReturn();
            assertThat(resultado.getResponse().getStatus()).as(cuerpo).isIn(400, 401);
            assertThat(resultado.getRequest().getSession(false)).as(cuerpo).isNull();
        }
    }

    // ------------------------------------------------------------------ CP-LIN-08

    @Test
    void login_fuerzaBrutaConRelojMutable_bloqueaYSeRecuperaAlAvanzarElReloj() throws Exception { // CP-LIN-08
        // Given
        String email = "lin08@ejemplo.es";
        registrarYActivar(email);

        // When: cinco fallos (cada uno desde una IP distinta: el bloqueo es de la cuenta, no de la IP)
        for (int i = 0; i < 5; i++) {
            login(email, CONTRASENA_INCORRECTA).andExpect(status().isUnauthorized());
        }

        // Then: la contraseña correcta durante el bloqueo recibe 429 con el tiempo que falta
        login(email, CONTRASENA).andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "30"));

        // Then: y sigue dando 429 mientras dure, sin prolongarlo
        RELOJ.avanzar(Duration.ofSeconds(10));
        login(email, CONTRASENA).andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "20"));

        // Then: al avanzar el reloj, entra
        RELOJ.avanzar(Duration.ofSeconds(20));
        login(email, CONTRASENA).andExpect(status().isOk());

        // Then: y el contador se reinició: cuatro fallos y un acierto no bloquean
        for (int i = 0; i < 4; i++) {
            login(email, CONTRASENA_INCORRECTA).andExpect(status().isUnauthorized());
        }
        login(email, CONTRASENA).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ CP-LIN-12

    @Test
    void login_passwordHashSinFormatoValido_devuelve401SinErrorInterno() throws Exception { // CP-LIN-12
        // Given: un documento cuyo campo password no tiene formato de hash (el codificador real no puede leerlo)
        String hashInvalido = "esto-no-es-un-hash";
        usuarios.insertOne(documentoCliente("lin12@ejemplo.es", hashInvalido));

        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            // When / Then: 401 como cualquier otro rechazo, sin error 500
            login("lin12@ejemplo.es", CONTRASENA).andExpect(status().isUnauthorized());

            // Then: el problema queda registrado (aviso o error, distinto del evento de auditoría) y sin secretos
            assertThat(logs.eventos()).anyMatch(evento -> evento.getLevel().isGreaterOrEqual(Level.WARN)
                    && !evento.getFormattedMessage().contains("LOGIN_FALLIDO"));
            // Solo se miran los eventos de nivel INFO o superior, que son los que emite la aplicación con su
            // configuración normal (el captador recoge también el DEBUG del entorno de pruebas, como el del driver
            // de MongoDB, que escribe los documentos que lee)
            assertThat(logs.eventos()).filteredOn(evento -> evento.getLevel().isGreaterOrEqual(Level.INFO))
                    .noneSatisfy(evento -> assertThat(evento.getFormattedMessage() + " "
                            + (evento.getThrowableProxy() == null ? ""
                                    : ThrowableProxyUtil.asString(evento.getThrowableProxy())))
                            .containsAnyOf(CONTRASENA, hashInvalido));
        }
    }
}
