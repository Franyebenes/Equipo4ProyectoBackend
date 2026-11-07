package com.esibuy.esibuy_backend.integracion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.esibuy.esibuy_backend.seguridad.GestorRegistroDosFactores;
import com.esibuy.esibuy_backend.seguridad.PendienteRegistroDosFactores;
import com.esibuy.esibuy_backend.servicio.Base32;
import com.esibuy.esibuy_backend.servicio.CifradorSecretos;
import com.esibuy.esibuy_backend.util.CapturadorLogs;
import com.jayway.jsonpath.JsonPath;
import com.mongodb.client.model.Filters;

/**
 * Primer paso del 2FA en el registro de punta a punta, con la seguridad real, los filtros reales y MongoDB real (HdU 2FA en
 * el registro, fase A). Casos: CP-2FA-434 a CP-2FA-441.
 *
 * Lo que fijan:
 *  - Pedir el QR no escribe nada en la base de datos (la cuenta todavia no existe): el secreto vive solo en la sesion, cifrado
 *    y ligado al correo, y se puede descifrar con ese correo como dato asociado.
 *  - La respuesta es la misma exista o no una cuenta con ese correo: no permite averiguar quien esta registrado.
 *  - Es publico pero exige token CSRF, y cuenta para el limite de peticiones de registro por IP, que comparte contador con
 *    el registro final: pedir el QR y registrarse son dos peticiones.
 *  - Ni el secreto, ni la URI, ni el correo en claro aparecen en los logs de la aplicacion.
 *
 * Terminada: ya no lleva la etiqueta pendiente-2fa y entra en la regresion.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class RegistroDosFactoresPreparacionIntegracionMongoTest {

    private static final String PREFIJO_EMAIL = "reg-2fa-";
    private static final String RUTA = "/api/auth/registro/2fa";

    /*
     * No hereda de IntegracionLoginBase porque esta prueba necesita un limite de registros por IP distinto (3, para poder
     * probarlo; la base lo sube a 1000) y dos propiedades dinamicas con el mismo nombre se pisan sin orden garantizado.
     */
    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registro) {
        registro.add("spring.mongodb.uri", () -> MongoCompartido.contenedor().getReplicaSetUrl("ESIBuy"));
        registro.add("esibuy.seguridad.pepper", () -> "pepper-de-integracion");
        registro.add("esibuy.limite-registro.max-peticiones", () -> "3");
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private MongoTemplate mongo;
    @Autowired
    private CifradorSecretos cifrador;
    @Autowired
    private GestorRegistroDosFactores gestor;

    // Estatico: JUnit crea una instancia por prueba y el filtro del limite recuerda las IP entre pruebas
    private static final AtomicInteger contadorIp = new AtomicInteger(1);

    @AfterEach
    void limpiar() {
        mongo.getCollection("usuarios").deleteMany(Filters.regex("email", "^" + PREFIJO_EMAIL));
    }

    // ------------------------------------------------------------------ utilidades

    private String correoNuevo() {
        return PREFIJO_EMAIL + UUID.randomUUID() + "@ejemplo.es";
    }

    private RequestPostProcessor desdeUnaIp(String ip) {
        return peticion -> {
            peticion.setRemoteAddr(ip);
            return peticion;
        };
    }

    private String ipNueva() {
        return "198.51.100." + contadorIp.getAndIncrement();
    }

    private MvcResult preparar(String correo, String ip, MockHttpSession sesion) throws Exception {
        var peticion = post(RUTA).with(csrf()).with(desdeUnaIp(ip)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + correo + "\"}");
        return mockMvc.perform(sesion == null ? peticion : peticion.session(sesion)).andReturn();
    }

    private static String cuerpoDe(MvcResult resultado) throws Exception {
        return resultado.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private long totalDeUsuarios() {
        return mongo.getCollection("usuarios").countDocuments();
    }

    // ------------------------------------------------------------------ CP-2FA-434 a CP-2FA-436

    @Test
    void preparar_devuelveElQrYNoEscribeNadaEnLaBaseDeDatos() throws Exception { // CP-2FA-434
        long usuariosAntes = totalDeUsuarios();
        long conDosFactoresAntes = mongo.getCollection("usuarios").countDocuments(Filters.exists("dosFactores"));
        String correo = correoNuevo();

        MvcResult resultado = preparar(correo, ipNueva(), null);

        assertThat(resultado.getResponse().getStatus()).as(cuerpoDe(resultado)).isEqualTo(200);
        String uri = JsonPath.read(cuerpoDe(resultado), "$.uri");
        String secreto = JsonPath.read(cuerpoDe(resultado), "$.secreto");
        assertThat(uri).startsWith("otpauth://totp/ESIBuy:").contains("secret=" + secreto).contains("issuer=ESIBuy")
                .contains(correo.replace("@", "%40"));
        assertThat(Base32.decodificar(secreto)).hasSize(20);
        // La cuenta todavia no existe: ni usuarios nuevos ni ningun dosFactores
        assertThat(totalDeUsuarios()).isEqualTo(usuariosAntes);
        assertThat(mongo.getCollection("usuarios").countDocuments(Filters.exists("dosFactores"))).isEqualTo(conDosFactoresAntes);
        assertThat(mongo.getCollection("usuarios").countDocuments(Filters.eq("email", correo))).isZero();
    }

    @Test
    void preparar_guardaEnLaSesionElSecretoCifradoYLigadoAlCorreo() throws Exception { // CP-2FA-435
        String correo = correoNuevo();

        MvcResult resultado = preparar(correo, ipNueva(), null);

        String secreto = JsonPath.read(cuerpoDe(resultado), "$.secreto");
        MockHttpSession sesion = (MockHttpSession) resultado.getRequest().getSession(false);
        var peticion = new org.springframework.mock.web.MockHttpServletRequest();
        peticion.setSession(sesion);
        PendienteRegistroDosFactores pendiente = gestor.vigente(peticion).orElseThrow();
        assertThat(pendiente.email()).isEqualTo(correo);
        assertThat(pendiente.secretoCifrado()).matches("^1:[A-Za-z0-9_-]+$").doesNotContain(secreto);
        // Se descifra con ese correo como dato asociado y da el mismo secreto que se mostro en el QR
        assertThat(cifrador.descifrar(pendiente.secretoCifrado(), correo)).isEqualTo(Base32.decodificar(secreto));
    }

    @Test
    void preparar_dalaMismaRespuestaExistaONoUnaCuentaConEseCorreo() throws Exception { // CP-2FA-436
        String registrado = correoNuevo();
        mongo.getCollection("usuarios").insertOne(new Document("_id", new ObjectId())
                .append("email", registrado).append("password", "hash").append("estado", "ACTIVO")
                .append("rol", List.of("CLIENTE"))
                .append("perfil", new Document("nombre", "Ana").append("apellidos", "Garcia")));

        MvcResult deRegistrado = preparar(registrado, ipNueva(), null);
        MvcResult deNuevo = preparar(correoNuevo(), ipNueva(), null);

        // Mismo estado, mismas cabeceras relevantes y mismos campos: no se puede saber quien esta registrado
        assertThat(deRegistrado.getResponse().getStatus()).isEqualTo(200).isEqualTo(deNuevo.getResponse().getStatus());
        Map<String, Object> camposRegistrado = JsonPath.read(cuerpoDe(deRegistrado), "$");
        Map<String, Object> camposNuevo = JsonPath.read(cuerpoDe(deNuevo), "$");
        assertThat(camposRegistrado.keySet()).isEqualTo(camposNuevo.keySet());
        assertThat(deRegistrado.getResponse().getHeader(HttpHeaders.CACHE_CONTROL))
                .isEqualTo(deNuevo.getResponse().getHeader(HttpHeaders.CACHE_CONTROL));
    }

    // ------------------------------------------------------------------ CP-2FA-437 a CP-2FA-441

    @Test
    void preparar_conUnCorreoInvalido_devuelve400ConElCampoYNoDejaPendiente() throws Exception { // CP-2FA-437
        MockHttpSession sesion = new MockHttpSession();

        MvcResult resultado = preparar("no-es-un-correo", ipNueva(), sesion);

        assertThat(resultado.getResponse().getStatus()).isEqualTo(400);
        assertThat((String) JsonPath.read(cuerpoDe(resultado), "$.errores.email[0]")).isEqualTo("FORMATO_INVALIDO");
        var peticion = new org.springframework.mock.web.MockHttpServletRequest();
        peticion.setSession(sesion);
        assertThat(gestor.vigente(peticion)).isEmpty();
    }

    @Test
    void preparar_exigeTokenCsrf() throws Exception { // CP-2FA-438
        MvcResult resultado = mockMvc.perform(post(RUTA).with(desdeUnaIp(ipNueva()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + correoNuevo() + "\"}")).andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void preparar_cuentaParaElLimiteDeRegistroPorIp_ysuContadorEsElMismoQueElDelRegistro() throws Exception { // CP-2FA-439
        String ip = ipNueva();

        // Dos peticiones de QR y un intento de registro (sin cuerpo valido: da 400, pero cuenta)
        assertThat(preparar(correoNuevo(), ip, null).getResponse().getStatus()).isEqualTo(200);
        assertThat(preparar(correoNuevo(), ip, null).getResponse().getStatus()).isEqualTo(200);
        assertThat(mockMvc.perform(post("/api/auth/registro").with(csrf()).with(desdeUnaIp(ip)).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andReturn().getResponse().getStatus()).isEqualTo(400);

        // El cuarto, de cualquiera de las dos rutas, ya no pasa
        MvcResult limitado = preparar(correoNuevo(), ip, null);
        assertThat(limitado.getResponse().getStatus()).isEqualTo(429);
        assertThat(limitado.getResponse().getHeader(HttpHeaders.RETRY_AFTER)).isNotBlank();
        // y otra IP no se ve afectada
        assertThat(preparar(correoNuevo(), ipNueva(), null).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void preparar_noExigeSesionNiRol() throws Exception { // CP-2FA-440
        MvcResult resultado = preparar(correoNuevo(), ipNueva(), null);

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void ningunLogDeLaAplicacionContieneElSecretoLaUriNiElCorreoEnClaro() throws Exception { // CP-2FA-441
        String correo = correoNuevo();
        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            MvcResult resultado = preparar(correo, ipNueva(), null);
            String secreto = JsonPath.read(cuerpoDe(resultado), "$.secreto");
            String uri = JsonPath.read(cuerpoDe(resultado), "$.uri");

            // Solo los logs de la aplicacion: a TRACE el driver de MongoDB escribe las consultas con datos del usuario
            List<String> deLaAplicacion = logs.eventos().stream()
                    .filter(evento -> evento.getLoggerName().startsWith("com.esibuy"))
                    .map(evento -> evento.getFormattedMessage()
                            + (evento.getThrowableProxy() == null ? "" : evento.getThrowableProxy().getMessage()))
                    .toList();
            assertThat(String.join("\n", deLaAplicacion)).contains("REGISTRO_DOS_FACTORES_INICIADO");
            assertThat(deLaAplicacion).allSatisfy(texto -> assertThat(texto).doesNotContain(secreto, uri, correo));
        }
    }
}
