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
 * Ciclo completo de la sesión tal y como lo hace el frontend, con servidor real, MongoDB real y Argon2id real:
 * token CSRF, login, consulta de /me, cierre de sesión y /me otra vez. Necesita Docker en marcha.
 *
 * Comprueba también lo que el frontend debe saber: tras el login la sesión se rota y el token CSRF anterior deja de
 * valer, así que hay que pedir uno nuevo antes de la siguiente petición que modifique datos (p. ej. el logout).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class SesionCicloCompletoIntegracionTest {

    private static final String EMAIL = "ciclo@ejemplo.es";
    private static final String CONTRASENA = ConstructorSolicitudCliente.CONTRASENA_VALIDA;

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

    private HttpRequest.Builder peticion(String ruta) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + puerto + ruta));
    }

    private HttpResponse<String> enviar(HttpRequest.Builder peticion) throws Exception {
        return cliente.send(peticion.build(), BodyHandlers.ofString());
    }

    private static String cookieDe(HttpResponse<?> respuesta) {
        return respuesta.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    }

    private record TokenCsrf(String cabecera, String valor) { }

    private TokenCsrf pedirToken(String cookie) throws Exception {
        HttpRequest.Builder peticion = peticion("/api/auth/csrf").GET();
        if (cookie != null) {
            peticion.header("Cookie", cookie);
        }
        HttpResponse<String> respuesta = enviar(peticion);
        assertEquals(200, respuesta.statusCode());
        return new TokenCsrf(JsonPath.read(respuesta.body(), "$.headerName"), JsonPath.read(respuesta.body(), "$.token"));
    }

    private HttpResponse<String> obtenerMe(String cookie) throws Exception {
        HttpRequest.Builder peticion = peticion("/api/auth/me").GET();
        if (cookie != null) {
            peticion.header("Cookie", cookie);
        }
        return enviar(peticion);
    }

    private HttpResponse<String> cerrarSesion(String cookie, TokenCsrf token) throws Exception {
        return enviar(peticion("/api/auth/logout").header("Cookie", cookie)
                .header(token.cabecera(), token.valor()).POST(BodyPublishers.noBody()));
    }

    // ------------------------------------------------------------------ ciclo completo

    @Test
    void sesion_cicloCompleto_loginMeLogoutYMeOtraVez() throws Exception {
        // Given: un cliente registrado y activado (lo activaría el panel de administración)
        String registro = objectMapper.writeValueAsString(
                ConstructorSolicitudCliente.unaSolicitudValida().conEmail(EMAIL).construir());
        assertEquals(201, enviar(peticion("/api/auth/registro").header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(registro))).statusCode());
        mongo.getCollection("usuarios").updateOne(Filters.eq("email", EMAIL), Updates.set("estado", "ACTIVO"));

        // When: pide el token y entra
        HttpResponse<String> primeraVisita = enviar(peticion("/api/auth/csrf").GET());
        String cookieAnonima = cookieDe(primeraVisita);
        TokenCsrf tokenAnterior = new TokenCsrf(JsonPath.read(primeraVisita.body(), "$.headerName"),
                JsonPath.read(primeraVisita.body(), "$.token"));
        HttpResponse<String> login = enviar(peticion("/api/auth/login")
                .header("Content-Type", "application/json").header("Cookie", cookieAnonima)
                .header(tokenAnterior.cabecera(), tokenAnterior.valor())
                .POST(BodyPublishers.ofString("{\"email\":\"" + EMAIL + "\",\"contrasena\":\"" + CONTRASENA + "\"}")));
        assertEquals(200, login.statusCode(), login.body());
        String cookieAutenticada = cookieDe(login);

        // Then: /me devuelve al usuario con la cookie nueva y 401 con la anónima anterior
        HttpResponse<String> me = obtenerMe(cookieAutenticada);
        assertEquals(200, me.statusCode(), me.body());
        assertThat((String) JsonPath.read(me.body(), "$.email")).isEqualTo(EMAIL);
        assertThat((String) JsonPath.read(me.body(), "$.rol")).isEqualTo("CLIENTE");
        assertEquals(401, obtenerMe(cookieAnonima).statusCode());
        assertEquals(401, obtenerMe(null).statusCode());

        // Then: el token CSRF de antes del login ya no vale para la sesión nueva: el frontend debe pedir otro
        assertEquals(403, cerrarSesion(cookieAutenticada, tokenAnterior).statusCode());
        assertEquals(200, obtenerMe(cookieAutenticada).statusCode());

        // When: con un token nuevo, cierra la sesión
        TokenCsrf tokenNuevo = pedirToken(cookieAutenticada);
        assertEquals(204, cerrarSesion(cookieAutenticada, tokenNuevo).statusCode());

        // Then: la sesión ya no existe
        assertEquals(401, obtenerMe(cookieAutenticada).statusCode());
    }
}
