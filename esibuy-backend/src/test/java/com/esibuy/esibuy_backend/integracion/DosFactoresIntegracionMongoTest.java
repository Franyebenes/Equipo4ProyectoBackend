package com.esibuy.esibuy_backend.integracion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.esibuy.esibuy_backend.servicio.Base32;
import com.esibuy.esibuy_backend.servicio.CifradorSecretos;
import com.esibuy.esibuy_backend.servicio.EnviadorCorreo;
import com.esibuy.esibuy_backend.servicio.GeneradorCodigosRecuperacion;
import com.esibuy.esibuy_backend.servicio.GeneradorTotp;
import com.esibuy.esibuy_backend.servicio.MensajeCorreo;
import com.esibuy.esibuy_backend.servicio.PlantillasCorreo;
import com.esibuy.esibuy_backend.util.CapturadorLogs;
import com.jayway.jsonpath.JsonPath;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;

/**
 * Activar, usar y quitar el segundo factor de punta a punta, con la seguridad real, MongoDB real y la sesion real (HdU
 * 2FA, fase 2). Casos: CP-2FA-271 a CP-2FA-282.
 *
 * Los codigos TOTP los calcula la prueba con el mismo generador que el servidor a partir del secreto que devuelve el
 * endpoint, igual que lo haria la aplicacion autenticadora del movil. Lo mas importante que fijan:
 *  - Lo guardado en MongoDB es solo el secreto cifrado y los hashes de los codigos de recuperacion: nada en claro, y el
 *    esquema del script de BBDD acepta lo que escribe la aplicacion.
 *  - Tras activar, desactivar o regenerar, las demas sesiones de la cuenta dejan de valer (401) y la que hizo el cambio
 *    sigue valiendo (se renueva), de modo que la persona no tiene que volver a entrar.
 *  - Un codigo TOTP no vale dos veces, y un codigo de recuperacion tampoco; los antiguos no valen tras regenerarlos.
 *  - El limite de fallos (aqui 3) corta con un 429 incluso al acertar despues, sin tocar la sesion ni otras cuentas.
 *  - Ni la contrasena, ni el codigo, ni el secreto, ni los codigos de recuperacion aparecen en los logs de la aplicacion
 *    ni en los correos de aviso.
 *
 * Terminada: ya no lleva la etiqueta pendiente-2fa y entra en la regresion.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class DosFactoresIntegracionMongoTest extends IntegracionLoginBase {

    private static final String PREFIJO_EMAIL = "e2e-2fa-";
    private static final String CONTRASENA = "Clave-De-Prueba-42!";
    private static final Pattern FORMATO_CODIGO_RECUPERACION = Pattern.compile("^[A-Z2-7]{4}(-[A-Z2-7]{4}){3}$");

    /** Sustituye el envio por SMTP por un grabador de correos. */
    static class GrabadorDeCorreos implements EnviadorCorreo {
        final List<MensajeCorreo> recibidos = new CopyOnWriteArrayList<>();

        @Override
        public void enviar(MensajeCorreo mensaje) {
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
     * El limite por cuenta se baja a 3 fallos para poder probarlo, y el de la IP se sube para que no intervenga: todas las
     * peticiones salen de la misma IP y varias pruebas fallan a proposito.
     */
    @DynamicPropertySource
    static void limitesDeLaPrueba(DynamicPropertyRegistry registro) {
        registro.add("esibuy.dosfactores.max-fallos-cuenta", () -> "3");
        registro.add("esibuy.dosfactores.max-por-ip", () -> "1000");
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private MongoTemplate mongo;
    @Autowired
    private PasswordEncoder codificador;
    @Autowired
    private GrabadorDeCorreos correo;
    @Autowired
    private GeneradorTotp totp;
    @Autowired
    private CifradorSecretos cifrador;
    @Autowired
    private GeneradorCodigosRecuperacion generadorCodigos;
    @Autowired
    private PlantillasCorreo plantillas;

    private MongoCollection<Document> usuarios;
    private final AtomicInteger contadorIp = new AtomicInteger(1);

    @BeforeEach
    void prepararColeccion() {
        usuarios = mongo.getCollection("usuarios");
        correo.recibidos.clear();
    }

    @AfterEach
    void limpiar() {
        usuarios.deleteMany(Filters.regex("email", "^" + PREFIJO_EMAIL));
    }

    // ------------------------------------------------------------------ utilidades

    private record Cuenta(ObjectId id, String email) {
    }

    private Cuenta crearCuenta() {
        ObjectId id = new ObjectId();
        String email = PREFIJO_EMAIL + UUID.randomUUID() + "@ejemplo.es";
        usuarios.insertOne(new Document("_id", id)
                .append("email", email)
                .append("password", codificador.encode(CONTRASENA))
                .append("estado", "ACTIVO")
                .append("rol", List.of("CLIENTE"))
                .append("perfil", new Document("nombre", "Ana").append("apellidos", "Garcia")));
        return new Cuenta(id, email);
    }

    private RequestPostProcessor desdeUnaIpNueva() {
        String ip = "203.0.113." + contadorIp.getAndIncrement();
        return peticion -> {
            peticion.setRemoteAddr(ip);
            return peticion;
        };
    }

    private MockHttpSession iniciarSesion(Cuenta cuenta) throws Exception {
        MvcResult resultado = mockMvc.perform(post("/api/auth/login").with(csrf()).with(desdeUnaIpNueva())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + cuenta.email() + "\",\"contrasena\":\"" + CONTRASENA + "\"}")).andReturn();
        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        return (MockHttpSession) resultado.getRequest().getSession(false);
    }

    private MockHttpServletRequestBuilder postJson(String ruta, MockHttpSession sesion, String cuerpo) {
        return post(ruta).session(sesion).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(cuerpo);
    }

    private static String cuerpoDe(MvcResult resultado) throws Exception {
        return resultado.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static MockHttpSession sesionDe(MvcResult resultado) {
        return (MockHttpSession) resultado.getRequest().getSession(false);
    }

    private int estadoDe(MockHttpSession sesion, String ruta) throws Exception {
        return mockMvc.perform(get(ruta).session(sesion)).andReturn().getResponse().getStatus();
    }

    private Document dosFactoresDe(Cuenta cuenta) {
        return usuarios.find(Filters.eq("_id", cuenta.id())).first().get("dosFactores", Document.class);
    }

    private static String cuerpoContrasena() {
        return "{\"contrasena\":\"" + CONTRASENA + "\"}";
    }

    private static String cuerpoVerificacion(String codigo) {
        return "{\"contrasena\":\"" + CONTRASENA + "\",\"codigo\":\"" + codigo + "\"}";
    }

    /** Lo que ve la persona tras activar: el secreto, la sesion que sigue valiendo y sus codigos de recuperacion. */
    private record Activado(byte[] secreto, MockHttpSession sesion, List<String> codigosRecuperacion, long paso) {
    }

    /** Activa el 2FA como lo haria la persona: pide el QR, "escanea" el secreto y confirma con el primer codigo. */
    private Activado activar(Cuenta cuenta, MockHttpSession sesion) throws Exception {
        MvcResult inicio = mockMvc.perform(postJson("/api/2fa/activacion", sesion, cuerpoContrasena())).andReturn();
        assertThat(inicio.getResponse().getStatus()).as(cuerpoDe(inicio)).isEqualTo(200);
        byte[] secreto = Base32.decodificar(JsonPath.read(cuerpoDe(inicio), "$.secreto"));
        long paso = totp.pasoActual();

        MvcResult confirmacion = mockMvc.perform(postJson("/api/2fa/activacion/confirmar", sesion,
                "{\"codigo\":\"" + totp.codigo(secreto, paso) + "\"}")).andReturn();
        assertThat(confirmacion.getResponse().getStatus()).as(cuerpoDe(confirmacion)).isEqualTo(200);
        List<String> codigos = JsonPath.read(cuerpoDe(confirmacion), "$.codigosRecuperacion");
        return new Activado(secreto, sesionDe(confirmacion), codigos, paso);
    }

    private void esperarCorreos(int cantidad) {
        await().atMost(Duration.ofSeconds(5)).until(() -> correo.recibidos.size() >= cantidad);
    }

    // ------------------------------------------------------------------ CP-2FA-271 / 272 / 273

    @Test
    void activar_guardaSoloElSecretoCifradoYLosHashes_yRenuevaLaSesionYCierraLasDemas() throws Exception { // CP-2FA-271
        Cuenta cuenta = crearCuenta();
        MockHttpSession sesion = iniciarSesion(cuenta);
        MockHttpSession otroDispositivo = iniciarSesion(cuenta);
        assertThat(estadoDe(sesion, "/api/2fa")).isEqualTo(200);

        // 1. Pide el QR: queda un secreto pendiente, cifrado y ligado a este usuario
        MvcResult inicio = mockMvc.perform(postJson("/api/2fa/activacion", sesion, cuerpoContrasena())).andReturn();
        String uri = JsonPath.read(cuerpoDe(inicio), "$.uri");
        String secretoBase32 = JsonPath.read(cuerpoDe(inicio), "$.secreto");
        byte[] secreto = Base32.decodificar(secretoBase32);
        assertThat(uri).startsWith("otpauth://totp/ESIBuy:").contains("secret=" + secretoBase32)
                .contains("issuer=ESIBuy").contains(cuenta.email().replace("@", "%40"));
        Document pendiente = dosFactoresDe(cuenta);
        assertThat(pendiente.getBoolean("activo")).isFalse();
        String guardado = pendiente.get("pendiente", Document.class).getString("secretoCifrado");
        assertThat(guardado).matches("^1:[A-Za-z0-9_-]+$").doesNotContain(secretoBase32);
        assertThat(cifrador.descifrar(guardado, cuenta.id().toHexString())).isEqualTo(secreto);
        assertThat(estadoDe(sesion, "/api/2fa")).isEqualTo(200); // iniciar no renueva ni cierra nada

        // 2. Confirma con el primer codigo de la aplicacion
        long paso = totp.pasoActual();
        MvcResult confirmacion = mockMvc.perform(postJson("/api/2fa/activacion/confirmar", sesion,
                "{\"codigo\":\"" + totp.codigo(secreto, paso) + "\"}")).andReturn();
        assertThat(confirmacion.getResponse().getStatus()).isEqualTo(200);
        List<String> codigos = JsonPath.read(cuerpoDe(confirmacion), "$.codigosRecuperacion");

        // Diez codigos con formato; en la base de datos solo sus hashes, junto al secreto definitivo ya sin pendiente
        assertThat(codigos).hasSize(10).doesNotHaveDuplicates()
                .allMatch(c -> FORMATO_CODIGO_RECUPERACION.matcher(c).matches());
        Document activo = dosFactoresDe(cuenta);
        assertThat(activo.getBoolean("activo")).isTrue();
        assertThat(activo.getString("secretoCifrado")).isEqualTo(guardado);
        assertThat(activo).doesNotContainKey("pendiente");
        assertThat(((Number) activo.get("ultimoPaso")).longValue()).isEqualTo(paso);
        List<Document> guardados = activo.getList("codigosRecuperacion", Document.class);
        assertThat(guardados).extracting(c -> c.getString("hash"))
                .containsExactlyInAnyOrderElementsOf(codigos.stream().map(generadorCodigos::hash).toList());
        assertThat(guardados).allSatisfy(c -> assertThat(c.getBoolean("usado")).isFalse());
        String documentoEntero = usuarios.find(Filters.eq("_id", cuenta.id())).first().toJson();
        assertThat(documentoEntero).doesNotContain(secretoBase32);
        codigos.forEach(c -> assertThat(documentoEntero).doesNotContain(c).doesNotContain(c.replace("-", "")));

        // 3. Sesiones: la anterior se invalida, el otro dispositivo tambien, y la nueva (renovada) sigue valiendo
        MockHttpSession renovada = sesionDe(confirmacion);
        assertThat(renovada).isNotSameAs(sesion);
        assertThat(estadoDe(sesion, "/api/auth/me")).isEqualTo(401);
        assertThat(estadoDe(otroDispositivo, "/api/auth/me")).isEqualTo(401);
        assertThat(estadoDe(renovada, "/api/auth/me")).isEqualTo(200);
        MvcResult estado = mockMvc.perform(get("/api/2fa").session(renovada)).andReturn();
        assertThat((Boolean) JsonPath.read(cuerpoDe(estado), "$.activo")).isTrue();
        assertThat((Integer) JsonPath.read(cuerpoDe(estado), "$.codigosRecuperacionDisponibles")).isEqualTo(10);
    }

    @Test
    void activar_avisaPorCorreoSinSecretosNiCodigos() throws Exception { // CP-2FA-272
        Cuenta cuenta = crearCuenta();
        Activado activado = activar(cuenta, iniciarSesion(cuenta));

        esperarCorreos(1);

        assertThat(correo.recibidos).singleElement().satisfies(mensaje -> {
            assertThat(mensaje.destinatario()).isEqualTo(cuenta.email());
            assertThat(mensaje.asunto()).isEqualTo(plantillas.dosFactoresActivado(cuenta.email()).asunto());
            assertThat(mensaje.cuerpo()).doesNotContain(Base32.codificar(activado.secreto()));
            activado.codigosRecuperacion().forEach(c -> assertThat(mensaje.cuerpo()).doesNotContain(c));
        });
    }

    @Test
    void activar_noAfectaALasOtrasCuentas() throws Exception { // CP-2FA-273
        Cuenta ana = crearCuenta();
        Cuenta luis = crearCuenta();
        MockHttpSession sesionDeLuis = iniciarSesion(luis);

        activar(ana, iniciarSesion(ana));

        assertThat(estadoDe(sesionDeLuis, "/api/auth/me")).isEqualTo(200);
        MvcResult estado = mockMvc.perform(get("/api/2fa").session(sesionDeLuis)).andReturn();
        assertThat((Boolean) JsonPath.read(cuerpoDe(estado), "$.activo")).isFalse();
        assertThat(usuarios.find(Filters.eq("_id", luis.id())).first()).doesNotContainKey("dosFactores");
    }

    // ------------------------------------------------------------------ CP-2FA-274 / 275

    @Test
    void unCodigoTotpNoVale2Veces_peroElSiguienteSi_yDesactivarQuitaTodo() throws Exception { // CP-2FA-274
        Cuenta cuenta = crearCuenta();
        Activado activado = activar(cuenta, iniciarSesion(cuenta));
        MockHttpSession sesion = activado.sesion();
        String codigoYaUsado = totp.codigo(activado.secreto(), activado.paso());

        // El codigo con el que se activo no vale otra vez
        MvcResult repetido = mockMvc.perform(postJson("/api/2fa/desactivacion", sesion, cuerpoVerificacion(codigoYaUsado)))
                .andReturn();
        assertThat(repetido.getResponse().getStatus()).isEqualTo(400);
        assertThat((String) JsonPath.read(cuerpoDe(repetido), "$.errores.codigo[0]")).isEqualTo("CODIGO_INCORRECTO");
        assertThat(dosFactoresDe(cuenta).getBoolean("activo")).isTrue();
        assertThat(estadoDe(sesion, "/api/2fa")).isEqualTo(200); // un fallo no toca la sesion

        // El del paso siguiente si (esta dentro de la ventana y es posterior al ultimo aceptado)
        String siguiente = totp.codigo(activado.secreto(), activado.paso() + 1);
        MvcResult desactivado = mockMvc.perform(postJson("/api/2fa/desactivacion", sesion, cuerpoVerificacion(siguiente)))
                .andReturn();

        assertThat(desactivado.getResponse().getStatus()).isEqualTo(204);
        assertThat(usuarios.find(Filters.eq("_id", cuenta.id())).first()).doesNotContainKey("dosFactores");
        // La sesion se renueva otra vez y la anterior ya no vale
        assertThat(estadoDe(sesion, "/api/auth/me")).isEqualTo(401);
        assertThat(estadoDe(sesionDe(desactivado), "/api/auth/me")).isEqualTo(200);
        esperarCorreos(2); // activado y desactivado
        assertThat(correo.recibidos).extracting(MensajeCorreo::asunto).contains(
                plantillas.dosFactoresActivado(cuenta.email()).asunto(),
                plantillas.dosFactoresDesactivado(cuenta.email()).asunto());
    }

    @Test
    void desactivarConUnCodigoDeRecuperacion_loConsumeYNoVuelveAValer() throws Exception { // CP-2FA-275
        Cuenta cuenta = crearCuenta();
        Activado activado = activar(cuenta, iniciarSesion(cuenta));
        String usado = activado.codigosRecuperacion().get(0);

        // Con el codigo escrito en minusculas y sin guiones, que es como lo teclearia alguien
        MvcResult desactivado = mockMvc.perform(postJson("/api/2fa/desactivacion", activado.sesion(),
                cuerpoVerificacion(usado.replace("-", "").toLowerCase()))).andReturn();

        assertThat(desactivado.getResponse().getStatus()).isEqualTo(204);
        assertThat(usuarios.find(Filters.eq("_id", cuenta.id())).first()).doesNotContainKey("dosFactores");
    }

    @Test
    void regenerarCodigos_conUnCodigoDeRecuperacion_dejaLosAntiguosSinValor() throws Exception { // CP-2FA-276
        Cuenta cuenta = crearCuenta();
        Activado activado = activar(cuenta, iniciarSesion(cuenta));
        List<String> antiguos = activado.codigosRecuperacion();

        MvcResult regenerado = mockMvc.perform(postJson("/api/2fa/codigos-recuperacion", activado.sesion(),
                cuerpoVerificacion(antiguos.get(0)))).andReturn();

        assertThat(regenerado.getResponse().getStatus()).isEqualTo(200);
        List<String> nuevos = JsonPath.read(cuerpoDe(regenerado), "$.codigosRecuperacion");
        assertThat(nuevos).hasSize(10).doesNotContainAnyElementsOf(antiguos);
        // Lo guardado son los hashes de los nuevos, todos sin usar
        assertThat(dosFactoresDe(cuenta).getList("codigosRecuperacion", Document.class))
                .extracting(c -> c.getString("hash"))
                .containsExactlyInAnyOrderElementsOf(nuevos.stream().map(generadorCodigos::hash).toList());
        // Un codigo antiguo (incluido el que se uso para regenerar) ya no sirve para nada
        MockHttpSession sesion = sesionDe(regenerado);
        for (int i = 0; i < 2; i++) {
            MvcResult intento = mockMvc.perform(postJson("/api/2fa/desactivacion", sesion,
                    cuerpoVerificacion(antiguos.get(i)))).andReturn();
            assertThat(intento.getResponse().getStatus()).isEqualTo(400);
        }
        assertThat(dosFactoresDe(cuenta).getBoolean("activo")).isTrue();
        // y uno nuevo si
        assertThat(mockMvc.perform(postJson("/api/2fa/desactivacion", sesion, cuerpoVerificacion(nuevos.get(0))))
                .andReturn().getResponse().getStatus()).isEqualTo(204);
        // La sesion se renovo al regenerar: la anterior ya no vale
        assertThat(estadoDe(activado.sesion(), "/api/auth/me")).isEqualTo(401);
    }

    // ------------------------------------------------------------------ CP-2FA-277 / 278 / 279

    @Test
    void conTresFallos_elCuartoIntentoDa429AunqueSeaCorrecto_sinTocarLaSesionNiOtrasCuentas() throws Exception { // CP-2FA-277
        Cuenta cuenta = crearCuenta();
        Cuenta otra = crearCuenta();
        Activado activado = activar(cuenta, iniciarSesion(cuenta));
        MockHttpSession sesion = activado.sesion();

        for (int i = 0; i < 3; i++) {
            MvcResult fallo = mockMvc.perform(postJson("/api/2fa/desactivacion", sesion, cuerpoVerificacion("000000")))
                    .andReturn();
            assertThat(fallo.getResponse().getStatus()).as("intento %d", i + 1).isEqualTo(400);
        }
        // Un codigo correcto, pero con el limite agotado
        String correcto = totp.codigo(activado.secreto(), activado.paso() + 1);
        MvcResult bloqueado = mockMvc.perform(postJson("/api/2fa/desactivacion", sesion, cuerpoVerificacion(correcto)))
                .andReturn();

        assertThat(bloqueado.getResponse().getStatus()).isEqualTo(429);
        assertThat(cuerpoDe(bloqueado)).doesNotContain(correcto, CONTRASENA);
        assertThat(dosFactoresDe(cuenta).getBoolean("activo")).isTrue();
        assertThat(estadoDe(sesion, "/api/2fa")).isEqualTo(200);
        // otra cuenta, aunque sea desde la misma IP, no se ve afectada
        MockHttpSession sesionDeOtra = iniciarSesion(otra);
        assertThat(mockMvc.perform(postJson("/api/2fa/activacion", sesionDeOtra, cuerpoContrasena())).andReturn()
                .getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void contrasenaIncorrecta_esUn400ConElCampoYSumaAlLimite() throws Exception { // CP-2FA-278
        Cuenta cuenta = crearCuenta();
        MockHttpSession sesion = iniciarSesion(cuenta);

        for (int i = 0; i < 3; i++) {
            MvcResult fallo = mockMvc.perform(postJson("/api/2fa/activacion", sesion,
                    "{\"contrasena\":\"Otra-Contrasena-99!\"}")).andReturn();
            assertThat(fallo.getResponse().getStatus()).isEqualTo(400);
            assertThat((String) JsonPath.read(cuerpoDe(fallo), "$.errores.contrasena[0]"))
                    .isEqualTo("CONTRASENA_INCORRECTA");
        }
        // A partir de aqui ni la contrasena buena sirve
        assertThat(mockMvc.perform(postJson("/api/2fa/activacion", sesion, cuerpoContrasena())).andReturn()
                .getResponse().getStatus()).isEqualTo(429);
        assertThat(usuarios.find(Filters.eq("_id", cuenta.id())).first()).doesNotContainKey("dosFactores");
    }

    @Test
    void reglasDeEstado_noSePuedeActivarDosVecesNiDesactivarSinHaberActivado() throws Exception { // CP-2FA-279
        Cuenta cuenta = crearCuenta();
        MockHttpSession sesion = iniciarSesion(cuenta);

        // Sin 2FA activo no hay nada que desactivar ni regenerar
        assertThat(mockMvc.perform(postJson("/api/2fa/desactivacion", sesion, cuerpoVerificacion("123456")))
                .andReturn().getResponse().getStatus()).isEqualTo(409);
        assertThat(mockMvc.perform(postJson("/api/2fa/codigos-recuperacion", sesion, cuerpoVerificacion("123456")))
                .andReturn().getResponse().getStatus()).isEqualTo(409);

        // Con el 2FA activo no se puede volver a empezar la activacion
        Activado activado = activar(cuenta, sesion);
        assertThat(mockMvc.perform(postJson("/api/2fa/activacion", activado.sesion(), cuerpoContrasena()))
                .andReturn().getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void confirmarSinHaberIniciadoLaActivacion_noEsVigente() throws Exception { // CP-2FA-280
        Cuenta cuenta = crearCuenta();

        MvcResult resultado = mockMvc.perform(postJson("/api/2fa/activacion/confirmar", iniciarSesion(cuenta),
                "{\"codigo\":\"123456\"}")).andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(400);
        assertThat((String) JsonPath.read(cuerpoDe(resultado), "$.errores.codigo[0]")).isEqualTo("ACTIVACION_NO_VIGENTE");
    }

    @Test
    void sinSesion_todoDa401() throws Exception { // CP-2FA-281
        assertThat(mockMvc.perform(get("/api/2fa")).andReturn().getResponse().getStatus()).isEqualTo(401);
        for (String ruta : List.of("/api/2fa/activacion", "/api/2fa/activacion/confirmar", "/api/2fa/desactivacion",
                "/api/2fa/codigos-recuperacion")) {
            assertThat(mockMvc.perform(post(ruta).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andReturn().getResponse().getStatus()).as(ruta).isEqualTo(401);
        }
    }

    // ------------------------------------------------------------------ CP-2FA-282

    @Test
    void ningunLogDeLaAplicacionContieneContrasenasCodigosNiSecretos() throws Exception { // CP-2FA-282
        Cuenta cuenta = crearCuenta();
        List<String> sensibles = new ArrayList<>(List.of(CONTRASENA, "Otra-Contrasena-99!"));
        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            MockHttpSession sesion = iniciarSesion(cuenta);
            mockMvc.perform(postJson("/api/2fa/activacion", sesion, "{\"contrasena\":\"Otra-Contrasena-99!\"}"));
            Activado activado = activar(cuenta, sesion);
            sensibles.add(Base32.codificar(activado.secreto()));
            sensibles.addAll(activado.codigosRecuperacion());
            sensibles.addAll(activado.codigosRecuperacion().stream().map(c -> c.replace("-", "")).toList());
            String codigoDeActivacion = totp.codigo(activado.secreto(), activado.paso());
            sensibles.add(codigoDeActivacion);
            mockMvc.perform(postJson("/api/2fa/desactivacion", activado.sesion(), cuerpoVerificacion(codigoDeActivacion)));
            mockMvc.perform(postJson("/api/2fa/desactivacion", activado.sesion(), cuerpoVerificacion("654321")));

            // Solo los logs de la aplicacion: a TRACE el driver de MongoDB escribe las consultas con los datos del usuario
            List<String> deLaAplicacion = logs.eventos().stream()
                    .filter(evento -> evento.getLoggerName().startsWith("com.esibuy"))
                    .map(evento -> evento.getFormattedMessage()
                            + (evento.getThrowableProxy() == null ? "" : evento.getThrowableProxy().getMessage()))
                    .toList();
            assertThat(deLaAplicacion).isNotEmpty().allSatisfy(texto -> sensibles.forEach(
                    sensible -> assertThat(texto).doesNotContain(sensible)));
        }
        // Y los correos de aviso tampoco
        esperarCorreos(1);
        correo.recibidos.forEach(mensaje -> sensibles.forEach(
                sensible -> assertThat(mensaje.cuerpo() + mensaje.asunto()).doesNotContain(sensible)));
    }
}
