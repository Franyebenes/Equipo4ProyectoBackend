package com.esibuy.esibuy_backend.integracion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.esibuy.esibuy_backend.excepcion.FalloEnvioCorreoException;
import com.esibuy.esibuy_backend.servicio.EnviadorCorreo;
import com.esibuy.esibuy_backend.servicio.GeneradorTokens;
import com.esibuy.esibuy_backend.servicio.MensajeCorreo;
import com.esibuy.esibuy_backend.servicio.ResultadoToken.Motivo;
import com.esibuy.esibuy_backend.servicio.ServicioTokensRecuperacion;
import com.esibuy.esibuy_backend.util.CapturadorLogs;
import com.mongodb.client.MongoCollection;

/**
 * Solicitud de recuperacion de contrasena de punta a punta: HTTP, seguridad, servicio, MongoDB real y correo
 * (HdU recuperacion de contrasena, fase 2). Casos: CP-REC-50 a CP-REC-60.
 *
 * El unico doble es el enviador de correo, que graba los mensajes en lugar de hablar con un SMTP (el SMTP real ya lo
 * prueba EnviadorCorreoSmtpTest). El envio sigue siendo asincrono de verdad, asi que las comprobaciones de "llega un
 * correo" esperan con Awaitility; las de "no llega nada" son deterministas porque en esos casos no se encola ninguna
 * tarea.
 *
 * Lo mas importante que fijan: la respuesta HTTP es identica en los cuatro casos (activa, pendiente, bloqueada y
 * correo desconocido), asi que un atacante no puede saber si un correo esta registrado, y el token que llega por
 * correo es el que despues valida el servicio de tokens, guardado solo como hash.
 *
 * Terminada: ya no lleva la etiqueta pendiente-recuperacion y entra en la regresion.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class RecuperacionIntegracionMongoTest extends IntegracionLoginBase {

    private static final String RUTA = "/api/auth/recuperacion";
    private static final Pattern ENLACE = Pattern.compile("restablecer-contrasena#token=([A-Za-z0-9_-]{43})");

    /** Sustituye el envio por SMTP por un grabador que permite fallar a voluntad. */
    static class GrabadorDeCorreos implements EnviadorCorreo {
        final List<MensajeCorreo> recibidos = new CopyOnWriteArrayList<>();
        final AtomicBoolean fallar = new AtomicBoolean();

        @Override
        public void enviar(MensajeCorreo mensaje) {
            if (fallar.get()) {
                throw new FalloEnvioCorreoException("No se pudo enviar el correo", null);
            }
            recibidos.add(mensaje);
        }
    }

    @TestConfiguration
    static class ConfiguracionDeCorreo {
        @Bean
        @Primary
        GrabadorDeCorreos grabadorDeCorreos() {
            return new GrabadorDeCorreos();
        }
    }

    /*
     * Esta clase prueba la solicitud, no los limites (eso lo hace RecuperacionLimitesIntegracionMongoTest): se los
     * deja tan altos que no intervienen, pues todas las peticiones salen de la misma IP y varias repiten cuenta.
     */
    @DynamicPropertySource
    static void limitesQueNoIntervienen(DynamicPropertyRegistry registro) {
        registro.add("esibuy.recuperacion.max-por-cuenta", () -> "1000");
        registro.add("esibuy.recuperacion.cooldown", () -> "PT0.001S");
        registro.add("esibuy.recuperacion.max-por-ip", () -> "1000");
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private MongoTemplate mongo;
    @Autowired
    private GrabadorDeCorreos correo;
    @Autowired
    private ServicioTokensRecuperacion servicioTokens;

    private MongoCollection<Document> usuarios;
    private MongoCollection<Document> tokens;

    @BeforeEach
    void limpiar() {
        usuarios = mongo.getCollection("usuarios");
        tokens = mongo.getCollection("tokens_recuperacion");
        usuarios.deleteMany(new Document());
        tokens.deleteMany(new Document());
        correo.recibidos.clear();
        correo.fallar.set(false);
    }

    // ------------------------------------------------------------------ utilidades

    private ObjectId crearUsuario(String email, String estado) {
        ObjectId id = new ObjectId();
        usuarios.insertOne(new Document("_id", id)
                .append("email", email)
                .append("password", "$argon2id$hash-de-prueba")
                .append("estado", estado)
                .append("rol", List.of("CLIENTE"))
                .append("perfil", new Document("nombre", "Ana").append("apellidos", "Garcia")));
        return id;
    }

    private MvcResult solicitar(String email) throws Exception {
        return mockMvc.perform(post(RUTA).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\"}")).andReturn();
    }

    private static String tokenDelCorreo(MensajeCorreo mensaje) {
        Matcher coincidencia = ENLACE.matcher(mensaje.cuerpo());
        assertThat(coincidencia.find()).as("el correo debe llevar el enlace de recuperacion").isTrue();
        return coincidencia.group(1);
    }

    // Margen antes de comprobar que NO ha llegado nada (en esos casos no hay ninguna tarea encolada).
    private static void esperarUnPoco() {
        await().pollDelay(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2)).until(() -> true);
    }

    // ------------------------------------------------------------------ CP-REC-50 / 51 / 52 / 53

    @Test
    void solicitar_cuentaActiva_llegaElEnlaceConUnTokenValidoGuardadoSoloComoHash() throws Exception { // CP-REC-50
        // Given
        ObjectId id = crearUsuario("ana@ejemplo.es", "ACTIVO");

        // When
        MvcResult resultado = solicitar("ana@ejemplo.es");

        // Then
        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(correo.recibidos).hasSize(1));
        MensajeCorreo recibido = correo.recibidos.get(0);
        assertThat(recibido.destinatario()).isEqualTo("ana@ejemplo.es");
        String token = tokenDelCorreo(recibido);

        // el token del correo es el que valida el servicio, y en la base de datos solo esta su hash
        assertThat(servicioTokens.validar(token).motivo()).isEqualTo(Motivo.VALIDO);
        assertThat(servicioTokens.validar(token).idUsuario()).isEqualTo(id.toHexString());
        assertThat(tokens.countDocuments()).isEqualTo(1);
        Document guardado = tokens.find().first();
        assertThat(guardado.getString("hashToken")).isEqualTo(GeneradorTokens.hash(token));
        assertThat(guardado.toJson()).doesNotContain(token);
        assertThat(guardado.getString("estado")).isEqualTo("VIGENTE");
        assertThat(guardado.getDate("caducaEn").getTime() - guardado.getDate("creadoEn").getTime())
                .isEqualTo(Duration.ofMinutes(15).toMillis());
    }

    @Test
    void solicitar_cuentaPendienteDeActivacion_llegaElAvisoSinEnlaceNiToken() throws Exception { // CP-REC-51
        // Given
        crearUsuario("ana@ejemplo.es", "DESACTIVADO");

        // When
        MvcResult resultado = solicitar("ana@ejemplo.es");

        // Then
        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(correo.recibidos).hasSize(1));
        assertThat(correo.recibidos.get(0).cuerpo()).containsIgnoringCase("pendiente de activación")
                .doesNotContainIgnoringCase("http");
        assertThat(tokens.countDocuments()).isZero();
    }

    @Test
    void solicitar_cuentaBloqueada_noLlegaNadaNiSeCreaToken() throws Exception { // CP-REC-52
        // Given
        crearUsuario("ana@ejemplo.es", "BLOQUEADO");

        // When
        MvcResult resultado = solicitar("ana@ejemplo.es");

        // Then
        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        esperarUnPoco();
        assertThat(correo.recibidos).isEmpty();
        assertThat(tokens.countDocuments()).isZero();
    }

    @Test
    void solicitar_correoNoRegistrado_noLlegaNadaNiSeCreaToken() throws Exception { // CP-REC-53
        // When
        MvcResult resultado = solicitar("nadie@ejemplo.es");

        // Then
        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        esperarUnPoco();
        assertThat(correo.recibidos).isEmpty();
        assertThat(tokens.countDocuments()).isZero();
    }

    // ------------------------------------------------------------------ CP-REC-54

    private static Map<String, Object> huellaDeLaRespuesta(MvcResult resultado) throws Exception {
        Map<String, Object> huella = new TreeMap<>();
        huella.put("estado", resultado.getResponse().getStatus());
        huella.put("tipo", resultado.getResponse().getContentType());
        huella.put("cuerpo", resultado.getResponse().getContentAsString(StandardCharsets.UTF_8));
        // Todas las cabeceras salvo las que cambian en cada peticion por diseno
        for (String nombre : resultado.getResponse().getHeaderNames()) {
            if (!nombre.equalsIgnoreCase("X-Correlation-Id") && !nombre.equalsIgnoreCase("Date")) {
                huella.put("cabecera:" + nombre, resultado.getResponse().getHeaders(nombre));
            }
        }
        return huella;
    }

    @Test
    void solicitar_losCuatroCasos_dan_exactamenteLaMismaRespuestaHttp() throws Exception { // CP-REC-54
        // Given: una cuenta de cada tipo
        crearUsuario("activa@ejemplo.es", "ACTIVO");
        crearUsuario("pendiente@ejemplo.es", "DESACTIVADO");
        crearUsuario("bloqueada@ejemplo.es", "BLOQUEADO");

        // When
        Map<String, Object> activa = huellaDeLaRespuesta(solicitar("activa@ejemplo.es"));
        Map<String, Object> pendiente = huellaDeLaRespuesta(solicitar("pendiente@ejemplo.es"));
        Map<String, Object> bloqueada = huellaDeLaRespuesta(solicitar("bloqueada@ejemplo.es"));
        Map<String, Object> desconocida = huellaDeLaRespuesta(solicitar("nadie@ejemplo.es"));

        // Then: ni el estado, ni el cuerpo, ni las cabeceras permiten distinguirlas
        assertThat(activa.get("estado")).isEqualTo(200);
        assertThat(pendiente).isEqualTo(activa);
        assertThat(bloqueada).isEqualTo(activa);
        assertThat(desconocida).isEqualTo(activa);
    }

    // ------------------------------------------------------------------ CP-REC-55 / 56 / 57

    @Test
    void solicitar_dosVeces_soloUnTokenQuedaVigenteYElOtroInvalidado() throws Exception { // CP-REC-55
        // Given
        crearUsuario("ana@ejemplo.es", "ACTIVO");

        // When: dos solicitudes seguidas (sus tareas pueden ejecutarse a la vez en el segundo plano)
        solicitar("ana@ejemplo.es");
        solicitar("ana@ejemplo.es");

        // Then: llegan dos correos y de sus dos tokens exactamente uno sigue valiendo
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(correo.recibidos).hasSize(2));
        List<Motivo> motivos = new ArrayList<>();
        for (MensajeCorreo mensaje : correo.recibidos) {
            motivos.add(servicioTokens.validar(tokenDelCorreo(mensaje)).motivo());
        }
        assertThat(motivos).containsExactlyInAnyOrder(Motivo.VALIDO, Motivo.INVALIDADO);
        assertThat(tokens.countDocuments(new Document("estado", "VIGENTE"))).isEqualTo(1);
    }

    @Test
    void solicitar_correoConEspaciosYMayusculas_encuentraLaCuenta() throws Exception { // CP-REC-56
        // Given
        crearUsuario("ana@ejemplo.es", "DESACTIVADO");

        // When
        solicitar("  ANA@Ejemplo.ES ");

        // Then: el correo va a la direccion guardada
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(correo.recibidos).hasSize(1));
        assertThat(correo.recibidos.get(0).destinatario()).isEqualTo("ana@ejemplo.es");
    }

    @Test
    void solicitar_fallaElEnvioDelCorreo_laRespuestaSigueSiendoLaMisma() throws Exception { // CP-REC-57
        // Given
        crearUsuario("ana@ejemplo.es", "ACTIVO");
        Map<String, Object> esperada = huellaDeLaRespuesta(solicitar("nadie@ejemplo.es"));
        correo.fallar.set(true);

        // When
        MvcResult resultado = solicitar("ana@ejemplo.es");

        // Then: el cliente no se entera del fallo
        assertThat(huellaDeLaRespuesta(resultado)).isEqualTo(esperada);
    }

    // ------------------------------------------------------------------ CP-REC-58 / 59 / 60

    @Test
    void solicitar_correoMalFormado_devuelve400SinEnviarNada() throws Exception { // CP-REC-58
        mockMvc.perform(post(RUTA).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"no-es-un-correo\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores.email[0]").value("FORMATO_INVALIDO"));

        esperarUnPoco();
        assertThat(correo.recibidos).isEmpty();
    }

    @Test
    void solicitar_sinTokenCsrf_devuelve403() throws Exception { // CP-REC-59
        crearUsuario("ana@ejemplo.es", "ACTIVO");

        mockMvc.perform(post(RUTA).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"ana@ejemplo.es\"}"))
                .andExpect(status().isForbidden());

        esperarUnPoco();
        assertThat(correo.recibidos).isEmpty();
        assertThat(tokens.countDocuments()).isZero();
    }

    @Test
    void solicitar_flujoCompleto_nadaEnElLogContieneElTokenNiElEnlace() throws Exception { // CP-REC-60
        // Given
        crearUsuario("ana@ejemplo.es", "ACTIVO");

        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            // When: una solicitud correcta y otra con el SMTP caido
            solicitar("ana@ejemplo.es");
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(correo.recibidos).hasSize(1));
            String token = tokenDelCorreo(correo.recibidos.get(0));
            correo.fallar.set(true);
            solicitar("ana@ejemplo.es");
            esperarUnPoco();

            // Then: nada de lo que escribe la aplicacion (no las trazas internas de Spring o del driver, que el
            // capturador recoge a nivel TRACE) incluye el token, el enlace ni el correo en claro
            List<String> textosDeLaAplicacion = logs.eventos().stream()
                    .filter(evento -> evento.getLoggerName().startsWith("com.esibuy"))
                    .map(evento -> evento.getFormattedMessage() + " " + evento.getMDCPropertyMap()
                            + (evento.getThrowableProxy() == null ? "" : evento.getThrowableProxy().getMessage()))
                    .toList();
            assertThat(textosDeLaAplicacion).isNotEmpty()
                    .noneMatch(texto -> texto.contains(token) || texto.contains("restablecer-contrasena")
                            || texto.contains("ana@ejemplo.es"));
        }
        assertThat(tokens.find(new Document("creadoEn", new Document("$lt", new Date(0)))).first()).isNull();
    }
}
