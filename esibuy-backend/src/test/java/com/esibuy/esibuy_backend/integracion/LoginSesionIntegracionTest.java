package com.esibuy.esibuy_backend.integracion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.List;

import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.MountableFile;

import com.esibuy.esibuy_backend.servicio.DiccionarioContrasenasProhibidas;
import com.esibuy.esibuy_backend.servicio.ValidadorDominioEmail;
import com.esibuy.esibuy_backend.util.ConstructorSolicitudCliente;
import com.jayway.jsonpath.JsonPath;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;

import tools.jackson.databind.ObjectMapper;

/**
 * Cookie y fijación de sesión con servidor real (RANDOM_PORT), porque MockMvc no aplica la
 * configuración de cookies del contenedor. Necesita Docker en marcha.
 *
 * Orden TDD (plan de pruebas, sección 8): paso 7.
 * Casos: CP-SES-13, CP-SES-14.
 * Colaboradores: Solo ValidadorDominioEmail. Servidor real, MongoDB real y Argon2id real.
 *
 * Notas de diseño de la clase:
 *  - Se usa el cliente HTTP del JDK, sin gestor de cookies: la cookie lleva Secure y el servidor de pruebas es HTTP,
 *    así que se leen las cabeceras Set-Cookie y se reenvía la cookie a mano.
 *  - Reglas de configuración que fijan estas pruebas: cookie HttpOnly, Secure y SameSite=Lax, con ruta /api y
 *    seguimiento de sesión solo por cookie (server.servlet.session.*), y 401 (no 403) para quien no está
 *    autenticado en una ruta protegida.
 *
 * Desarrollada con el ciclo Red-Green-Refactor: primero el test (Arrange, Act, Assert), después el código
 * mínimo que lo hace pasar. Terminada: ya no lleva la etiqueta pendiente-login y entra en la regresión.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class LoginSesionIntegracionTest {

    private static final String CONTRASENA = ConstructorSolicitudCliente.CONTRASENA_VALIDA;
    private static final String RUTA_PROTEGIDA = "/api/pedidos";

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("mongo/init-esibuy.js"), "/tmp/init-esibuy.js");

    @DynamicPropertySource
    static void configurarPropiedades(DynamicPropertyRegistry registro) {
        registro.add("spring.mongodb.uri", () -> MONGO.getReplicaSetUrl("ESIBuy"));
        registro.add("esibuy.seguridad.pepper", () -> "pepper-de-integracion");
        registro.add("esibuy.limite-registro.max-peticiones", () -> "1000");
    }

    @BeforeAll
    static void aplicarElScriptDeBaseDeDatos() throws Exception {
        ExecResult resultado = MONGO.execInContainer("mongosh", "--quiet", "--file", "/tmp/init-esibuy.js");
        assertEquals(0, resultado.getExitCode(), "Fallo al ejecutar el script de BBDD: " + resultado.getStderr());
    }

    @Value("${local.server.port}")
    private int puerto;
    @Autowired
    private MongoTemplate mongo;
    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ValidadorDominioEmail validadorDominioEmail;
    @MockitoBean
    private DiccionarioContrasenasProhibidas diccionario;

    private final HttpClient cliente = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @BeforeEach
    void limpiarUsuariosYPrepararMocks() {
        mongo.getCollection("usuarios").deleteMany(new Document());
        when(validadorDominioEmail.tieneDominioValido(anyString())).thenReturn(true);
    }

    // ------------------------------------------------------------------ utilidades

    private HttpResponse<String> enviar(HttpRequest.Builder peticion) throws Exception {
        return cliente.send(peticion.build(), BodyHandlers.ofString());
    }

    private HttpRequest.Builder peticion(String ruta) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + puerto + ruta));
    }

    /** Registra un cliente con el endpoint real y lo activa, como haría el panel de administración. */
    private void registrarYActivar(String email) throws Exception {
        String cuerpo = objectMapper.writeValueAsString(
                ConstructorSolicitudCliente.unaSolicitudValida().conEmail(email).construir());
        HttpResponse<String> respuesta = enviar(peticion("/api/auth/registro")
                .header("Content-Type", "application/json").POST(BodyPublishers.ofString(cuerpo)));
        assertEquals(201, respuesta.statusCode(), respuesta.body());
        mongo.getCollection("usuarios").updateOne(Filters.eq("email", email), Updates.set("estado", "ACTIVO"));
    }

    /** Primera cabecera Set-Cookie de la respuesta (la de la sesión): "nombre=valor; atributos...". */
    private static String cookieEmitida(HttpResponse<?> respuesta) {
        List<String> cookies = respuesta.headers().allValues("Set-Cookie");
        assertThat(cookies).as("Set-Cookie").hasSize(1);
        return cookies.get(0);
    }

    private static String nombreYValor(String setCookie) {
        return setCookie.split(";", 2)[0];
    }

    private static String valor(String setCookie) {
        return nombreYValor(setCookie).split("=", 2)[1];
    }

    /** Un visitante anónimo pide el token CSRF: recibe la cookie de su sesión y el token. */
    private record VisitaInicial(String cookie, String cabeceraCsrf, String tokenCsrf) { }

    private VisitaInicial pedirTokenCsrf() throws Exception {
        HttpResponse<String> respuesta = enviar(peticion("/api/auth/csrf").GET());
        assertEquals(200, respuesta.statusCode());
        return new VisitaInicial(nombreYValor(cookieEmitida(respuesta)),
                JsonPath.read(respuesta.body(), "$.headerName"), JsonPath.read(respuesta.body(), "$.token"));
    }

    private HttpResponse<String> iniciarSesion(VisitaInicial visita, String email) throws Exception {
        String cuerpo = "{\"email\":\"" + email + "\",\"contrasena\":\"" + CONTRASENA + "\"}";
        return enviar(peticion("/api/auth/login")
                .header("Content-Type", "application/json")
                .header("Cookie", visita.cookie())
                .header(visita.cabeceraCsrf(), visita.tokenCsrf())
                .POST(BodyPublishers.ofString(cuerpo)));
    }

    private int estadoDeRutaProtegida(String ruta, String cookie) throws Exception {
        HttpRequest.Builder peticion = peticion(ruta).GET();
        if (cookie != null) {
            peticion.header("Cookie", cookie);
        }
        return enviar(peticion).statusCode();
    }

    // ------------------------------------------------------------------ CP-SES-13

    @Test
    void login_cookieRealEmitida_tieneLasBanderasDeSeguridadYValorOpaco() throws Exception { // CP-SES-13
        // Given
        String email = "ses13@ejemplo.es";
        registrarYActivar(email);
        VisitaInicial visita = pedirTokenCsrf();

        // When
        HttpResponse<String> login = iniciarSesion(visita, email);

        // Then: login correcto y una cookie con HttpOnly, Secure, SameSite=Lax y ruta acotada
        assertEquals(200, login.statusCode(), login.body());
        String cookie = cookieEmitida(login);
        assertThat(cookie).contains("HttpOnly", "Secure", "SameSite=Lax", "Path=/api");

        // Then: valor opaco, sin correo, rol ni hash
        assertThat(valor(cookie)).matches("[A-Za-z0-9._-]{16,}").doesNotContain("ses13", "CLIENTE", "argon2");

        // Then: seguimiento solo por cookie: nada de identificadores en la respuesta ni en la URL
        assertThat(login.body().toLowerCase()).doesNotContain("jsessionid");
        assertThat(estadoDeRutaProtegida(RUTA_PROTEGIDA, nombreYValor(cookie))).isNotIn(401, 403);
        assertThat(estadoDeRutaProtegida(RUTA_PROTEGIDA + ";jsessionid=" + valor(cookie), null)).isIn(400, 401);
    }

    // ------------------------------------------------------------------ CP-SES-14

    @Test
    void login_conCookieDeSesionPreestablecida_rotaElIdentificadorYInvalidaLaAntigua() throws Exception { // CP-SES-14
        // Given: un cliente que ya tiene una cookie de sesión (la anónima que recibió con el token CSRF)
        String email = "ses14@ejemplo.es";
        registrarYActivar(email);
        VisitaInicial visita = pedirTokenCsrf();
        String cookieAntigua = visita.cookie();

        // When: inicia sesión con esa cookie
        HttpResponse<String> login = iniciarSesion(visita, email);

        // Then: recibe una cookie nueva, con otro identificador
        assertEquals(200, login.statusCode(), login.body());
        String cookieNueva = nombreYValor(cookieEmitida(login));
        assertThat(valor(cookieNueva)).isNotEqualTo(valor(cookieAntigua));

        // Then: la nueva es válida y la antigua ya no sirve (401)
        assertThat(estadoDeRutaProtegida(RUTA_PROTEGIDA, cookieNueva)).isNotIn(401, 403);
        assertThat(estadoDeRutaProtegida(RUTA_PROTEGIDA, cookieAntigua)).isEqualTo(401);
    }
}
