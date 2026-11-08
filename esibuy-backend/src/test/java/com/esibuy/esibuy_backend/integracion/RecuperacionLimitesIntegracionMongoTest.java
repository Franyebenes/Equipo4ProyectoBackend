package com.esibuy.esibuy_backend.integracion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.esibuy.esibuy_backend.servicio.EnviadorCorreo;
import com.esibuy.esibuy_backend.servicio.MensajeCorreo;
import com.esibuy.esibuy_backend.util.CapturadorLogs;
import com.esibuy.esibuy_backend.util.RelojMutable;
import com.mongodb.client.MongoCollection;

/**
 * Limites de la recuperacion de contrasena de punta a punta, con la configuracion real (5 al dia por cuenta, 60 s
 * entre solicitudes, 10 por hora por IP), MongoDB real y reloj manual (HdU recuperacion de contrasena, fase 3).
 * Casos: CP-LIM-50 a CP-LIM-59.
 *
 * Lo que fijan, visto desde fuera:
 *  - Superado un limite el cliente recibe EXACTAMENTE la misma respuesta (estado, cuerpo y cabeceras) que en una
 *    solicitud normal; solo cambia que no se crea token ni se manda correo.
 *  - Cuentan las cuentas ACTIVO y DESACTIVADO; las bloqueadas y los correos desconocidos solo consumen cupo de IP.
 *  - El bloqueo del login y estos limites son independientes en los dos sentidos.
 *
 * Los contadores viven en el contexto de Spring, que se comparte entre tests: cada test usa sus propios correos e IP.
 *
 * Terminada: ya no lleva la etiqueta pendiente-recuperacion y entra en la regresion.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class RecuperacionLimitesIntegracionMongoTest extends IntegracionLoginBase {

    private static final String RUTA = "/api/auth/recuperacion";
    private static final String RUTA_LOGIN = "/api/auth/login";
    private static final String CONTRASENA = "Una-contrasena-de-prueba-1";
    private static final Duration COOLDOWN = Duration.ofSeconds(60);

    private static final RelojMutable RELOJ =
            new RelojMutable(Instant.parse("2026-10-17T10:00:00Z"), ZoneId.of("Europe/Madrid"));
    private static final AtomicInteger CONTADOR = new AtomicInteger();

    static class GrabadorDeCorreos implements EnviadorCorreo {
        final List<MensajeCorreo> recibidos = new CopyOnWriteArrayList<>();

        @Override
        public void enviar(MensajeCorreo mensaje) {
            recibidos.add(mensaje);
        }

        long para(String destinatario) {
            return recibidos.stream().filter(m -> m.destinatario().equals(destinatario)).count();
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
    private GrabadorDeCorreos correo;
    @Autowired
    private PasswordEncoder codificador;

    private MongoCollection<Document> usuarios;
    private MongoCollection<Document> tokens;

    @BeforeEach
    void prepararColecciones() {
        usuarios = mongo.getCollection("usuarios");
        tokens = mongo.getCollection("tokens_recuperacion");
        usuarios.deleteMany(new Document());
        tokens.deleteMany(new Document());
    }

    // ------------------------------------------------------------------ utilidades

    private static String correoNuevo() {
        return "usuario" + CONTADOR.incrementAndGet() + "@ejemplo.es";
    }

    private static String ipNueva() {
        int n = CONTADOR.incrementAndGet();
        return "10.9." + (n / 250) + "." + (n % 250 + 1);
    }

    private static RequestPostProcessor desdeLaIp(String ip) {
        return peticion -> {
            peticion.setRemoteAddr(ip);
            return peticion;
        };
    }

    private void crearUsuario(String email, String estado) {
        usuarios.insertOne(new Document("_id", new ObjectId())
                .append("email", email)
                .append("password", codificador.encode(CONTRASENA))
                .append("estado", estado)
                .append("rol", List.of("CLIENTE"))
                .append("perfil", new Document("nombre", "Ana").append("apellidos", "Garcia")));
    }

    private MvcResult solicitar(String email, String ip) throws Exception {
        return mockMvc.perform(post(RUTA).with(csrf()).with(desdeLaIp(ip)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\"}")).andReturn();
    }

    private MvcResult login(String email, String contrasena) throws Exception {
        return mockMvc.perform(post(RUTA_LOGIN).with(csrf()).with(desdeLaIp(ipNueva()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"contrasena\":\"" + contrasena + "\"}")).andReturn();
    }

    /** Solicita y, si se espera que llegue un correo mas, espera a que llegue (el envio es asincrono). */
    private void solicitarYEsperarCorreo(String email, String ip, long correosEsperados) throws Exception {
        assertThat(solicitar(email, ip).getResponse().getStatus()).isEqualTo(200);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(correo.para(email)).isEqualTo(correosEsperados));
    }

    private static Map<String, Object> huellaDeLaRespuesta(MvcResult resultado) throws Exception {
        Map<String, Object> huella = new TreeMap<>();
        huella.put("estado", resultado.getResponse().getStatus());
        huella.put("tipo", resultado.getResponse().getContentType());
        huella.put("cuerpo", resultado.getResponse().getContentAsString(StandardCharsets.UTF_8));
        for (String nombre : resultado.getResponse().getHeaderNames()) {
            if (!nombre.equalsIgnoreCase("X-Correlation-Id") && !nombre.equalsIgnoreCase("Date")) {
                huella.put("cabecera:" + nombre, resultado.getResponse().getHeaders(nombre));
            }
        }
        return huella;
    }

    // Margen antes de comprobar que NO ha llegado nada (en esos casos no hay ninguna tarea encolada).
    private static void esperarUnPoco() {
        await().pollDelay(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2)).until(() -> true);
    }

    // ------------------------------------------------------------------ CP-LIM-50 / 51: cuenta

    @Test
    void solicitar_laSextaDelDia_noEnviaNadaYLaRespuestaEsIdentica() throws Exception { // CP-LIM-50
        // Given: una cuenta activa que pide cinco veces, separadas por la espera
        String email = correoNuevo();
        String ip = ipNueva();
        crearUsuario(email, "ACTIVO");
        MvcResult normal = null;
        for (int i = 1; i <= 5; i++) {
            if (i > 1) {
                RELOJ.avanzar(COOLDOWN);
            }
            solicitarYEsperarCorreo(email, ip, i);
            if (i == 1) {
                normal = solicitar(correoNuevo(), ipNueva()); // una solicitud cualquiera, para comparar la respuesta
            }
        }

        // When: la sexta
        RELOJ.avanzar(COOLDOWN);
        MvcResult sexta = solicitar(email, ip);

        // Then: misma respuesta que una normal, y ni correo ni token nuevo
        assertThat(huellaDeLaRespuesta(sexta)).isEqualTo(huellaDeLaRespuesta(normal));
        esperarUnPoco();
        assertThat(correo.para(email)).isEqualTo(5);
        assertThat(tokens.countDocuments()).isEqualTo(5);
    }

    @Test
    void solicitar_dosVecesSeguidas_laSegundaEsperaAlCooldown() throws Exception { // CP-LIM-51
        // Given
        String email = correoNuevo();
        String ip = ipNueva();
        crearUsuario(email, "ACTIVO");
        solicitarYEsperarCorreo(email, ip, 1);

        // When: una segunda solicitud inmediata, y otra 59 s despues
        MvcResult inmediata = solicitar(email, ip);
        RELOJ.avanzar(COOLDOWN.minusSeconds(1));
        solicitar(email, ip);
        esperarUnPoco();

        // Then: no llega nada mas, y con la misma respuesta
        assertThat(correo.para(email)).isEqualTo(1);
        assertThat(inmediata.getResponse().getStatus()).isEqualTo(200);

        // y cumplidos los 60 s desde la unica solicitud permitida, si llega
        RELOJ.avanzar(Duration.ofSeconds(1));
        solicitarYEsperarCorreo(email, ip, 2);
    }

    @Test
    void solicitar_cumplidas24HorasDeLaPrimera_vuelveAFuncionar() throws Exception { // CP-LIM-52
        // Given: el cupo diario agotado
        String email = correoNuevo();
        String ip = ipNueva();
        crearUsuario(email, "ACTIVO");
        for (int i = 1; i <= 5; i++) {
            if (i > 1) {
                RELOJ.avanzar(COOLDOWN);
            }
            solicitarYEsperarCorreo(email, ip, i);
        }
        RELOJ.avanzar(COOLDOWN);
        solicitar(email, ip);
        esperarUnPoco();
        assertThat(correo.para(email)).isEqualTo(5);

        // When: pasan 24 h desde la primera (ya hay margen de sobra: han pasado 5 min + las 24 h)
        RELOJ.avanzar(Duration.ofHours(24));

        // Then
        solicitarYEsperarCorreo(email, ip, 6);
    }

    @Test
    void solicitar_cuentaPendienteDeActivacion_tambienTieneElLimiteDiario() throws Exception { // CP-LIM-53
        String email = correoNuevo();
        String ip = ipNueva();
        crearUsuario(email, "DESACTIVADO");
        for (int i = 1; i <= 5; i++) {
            if (i > 1) {
                RELOJ.avanzar(COOLDOWN);
            }
            solicitarYEsperarCorreo(email, ip, i);
        }

        RELOJ.avanzar(COOLDOWN);
        solicitar(email, ip);
        esperarUnPoco();

        // el aviso de cuenta pendiente tampoco puede usarse para inundar de correos a nadie
        assertThat(correo.para(email)).isEqualTo(5);
        assertThat(tokens.countDocuments()).isZero();
    }

    // ------------------------------------------------------------------ CP-LIM-54 / 55: IP

    @Test
    void solicitar_laOnceDeUnaMismaIp_noEnviaNadaYOtraIpSiFunciona() throws Exception { // CP-LIM-54
        // Given: once cuentas activas distintas, todas pidiendo desde la misma IP
        String ip = ipNueva();
        MvcResult normal = null;
        for (int i = 1; i <= 10; i++) {
            String email = correoNuevo();
            crearUsuario(email, "ACTIVO");
            solicitarYEsperarCorreo(email, ip, 1);
            if (i == 1) {
                normal = solicitar(correoNuevo(), ipNueva());
            }
        }

        // When: la undecima
        String ultima = correoNuevo();
        crearUsuario(ultima, "ACTIVO");
        MvcResult limitada = solicitar(ultima, ip);

        // Then: misma respuesta, sin correo; y esa misma cuenta, desde otra IP, si recibe el suyo
        assertThat(huellaDeLaRespuesta(limitada)).isEqualTo(huellaDeLaRespuesta(normal));
        esperarUnPoco();
        assertThat(correo.para(ultima)).isZero();
        solicitarYEsperarCorreo(ultima, ipNueva(), 1);
    }

    @Test
    void solicitar_correosDesconocidosYCuentasBloqueadas_consumenElCupoDeLaIp() throws Exception { // CP-LIM-55
        // Given: una IP que barre correos inexistentes y una cuenta bloqueada (no mandan nada)
        String ip = ipNueva();
        String bloqueada = correoNuevo();
        crearUsuario(bloqueada, "BLOQUEADO");
        solicitar(bloqueada, ip);
        for (int i = 0; i < 9; i++) {
            solicitar(correoNuevo(), ip);
        }

        // When: ahora pide una cuenta activa legitima desde esa misma IP
        String activa = correoNuevo();
        crearUsuario(activa, "ACTIVO");
        solicitar(activa, ip);

        // Then: el cupo de la IP ya estaba agotado, asi que no recibe nada
        esperarUnPoco();
        assertThat(correo.para(activa)).isZero();
    }

    // ------------------------------------------------------------------ CP-LIM-56 / 57: independencia del login

    @Test
    void recuperar_cuentaConElLoginBloqueadoPorFallos_sigueFuncionando() throws Exception { // CP-LIM-56
        // Given: cinco contrasenas incorrectas seguidas bloquean el login de la cuenta
        String email = correoNuevo();
        crearUsuario(email, "ACTIVO");
        for (int i = 0; i < 5; i++) {
            assertThat(login(email, "Otra-Contrasena-99").getResponse().getStatus()).isEqualTo(401);
        }
        assertThat(login(email, CONTRASENA).getResponse().getStatus()).isEqualTo(429);

        // When / Then: aun asi, puede recuperar su contrasena
        solicitarYEsperarCorreo(email, ipNueva(), 1);
    }

    @Test
    void login_cuentaConElLimiteDeRecuperacionAgotado_sigueFuncionandoYSinBloqueos() throws Exception { // CP-LIM-57
        // Given: el cupo de recuperacion agotado
        String email = correoNuevo();
        String ip = ipNueva();
        crearUsuario(email, "ACTIVO");
        for (int i = 1; i <= 5; i++) {
            if (i > 1) {
                RELOJ.avanzar(COOLDOWN);
            }
            solicitarYEsperarCorreo(email, ip, i);
        }
        RELOJ.avanzar(COOLDOWN);
        solicitar(email, ip);

        // When / Then: el login con la contrasena buena entra, y uno con la mala da 401 (no 429: no hay bloqueo)
        assertThat(login(email, CONTRASENA).getResponse().getStatus()).isEqualTo(200);
        assertThat(login(email, "Otra-Contrasena-99").getResponse().getStatus()).isEqualTo(401);
    }

    // ------------------------------------------------------------------ CP-LIM-58

    @Test
    void solicitar_limitada_quedaAuditadaConSuMotivo() throws Exception { // CP-LIM-58
        String email = correoNuevo();
        String ip = ipNueva();
        crearUsuario(email, "ACTIVO");
        solicitarYEsperarCorreo(email, ip, 1);

        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            solicitar(email, ip);

            assertThat(logs.eventos()).filteredOn(e -> e.getLoggerName().startsWith("com.esibuy"))
                    .anySatisfy(evento -> assertThat(evento.getFormattedMessage())
                            .contains("RECUPERACION_LIMITADA", "motivo=COOLDOWN").doesNotContain(email));
        }
    }
}
