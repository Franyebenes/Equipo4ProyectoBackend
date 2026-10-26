package com.esibuy.esibuy_backend.integracion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.esibuy.esibuy_backend.servicio.Base32;
import com.esibuy.esibuy_backend.servicio.EnviadorCorreo;
import com.esibuy.esibuy_backend.servicio.GeneradorTotp;
import com.esibuy.esibuy_backend.servicio.MensajeCorreo;
import com.esibuy.esibuy_backend.servicio.ServicioTokensRecuperacion;
import com.esibuy.esibuy_backend.util.CapturadorLogs;
import com.jayway.jsonpath.JsonPath;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;

/**
 * Login en dos pasos de punta a punta, con la seguridad real, MongoDB real y la sesion real (HdU 2FA, fase 3).
 * Casos: CP-2FA-363 a CP-2FA-376.
 *
 * Las cuentas con 2FA se preparan por la API de la fase 2 (como lo haria la persona) y los codigos TOTP los calcula la prueba
 * con el mismo generador que el servidor, a partir del secreto, como la aplicacion autenticadora. Lo mas importante que fijan:
 *  - Con el 2FA activo, la contrasena sola NO da sesion: el login responde que falta el segundo factor y con esa sesion
 *    pendiente no se puede entrar a nada (401). Solo un codigo correcto la convierte en una sesion, con identificador nuevo.
 *  - Un codigo TOTP no vale dos veces (ni en el login ni entre login y regenerar los codigos); un codigo de recuperacion se consume.
 *  - Restablecer la contrasena NO desactiva ni salta el 2FA: tras el restablecimiento, el login vuelve a pedir el codigo.
 *  - El limite de fallos del segundo factor (aqui 3 por cuenta) corta con un 429 incluso al acertar despues, y es
 *    independiente del login: no bloquea la contrasena ni a otras cuentas.
 *  - Una cuenta que dejo de poder entrar entre los dos pasos (bloqueada, desactivada, sin 2FA) no completa el login.
 *  - Dos peticiones simultaneas con el mismo codigo: solo una entra.
 *  - Ni la contrasena, ni los codigos, ni el secreto aparecen en los logs de la aplicacion.
 *
 * Terminada: ya no lleva la etiqueta pendiente-2fa y entra en la regresion.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class LoginDosFactoresIntegracionMongoTest extends IntegracionLoginBase {

    private static final String PREFIJO_EMAIL = "login-2fa-";
    private static final String CONTRASENA = "Clave-De-Prueba-42!";
    private static final String CONTRASENA_NUEVA = "Nueva-Clave-Segura-2026-x!";
    private static final String CODIGO_MALO = "000000";

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
     * El limite por cuenta se baja a 3 fallos para poder probarlo, y el de la IP se sube para que no intervenga: varias
     * pruebas fallan a proposito y todas salen de la misma IP del socket (los inicios de sesion usan IP distintas).
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
    private GeneradorTotp totp;
    @Autowired
    private ServicioTokensRecuperacion tokens;
    @Autowired
    private GrabadorDeCorreos correo;

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

    /** Una cuenta que ya tiene el 2FA activo: su secreto, sus codigos de recuperacion y el ultimo paso aceptado. */
    private record CuentaConDosFactores(Cuenta cuenta, byte[] secreto, List<String> codigosRecuperacion, long ultimoPaso) {
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

    private MvcResult login(Cuenta cuenta, String contrasena) throws Exception {
        return mockMvc.perform(post("/api/auth/login").with(csrf()).with(desdeUnaIpNueva())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + cuenta.email() + "\",\"contrasena\":\"" + contrasena + "\"}")).andReturn();
    }

    private static MockHttpSession sesionDe(MvcResult resultado) {
        return (MockHttpSession) resultado.getRequest().getSession(false);
    }

    private MvcResult verificar(MockHttpSession sesion, String codigo) throws Exception {
        return mockMvc.perform(post("/api/auth/2fa/verificar").session(sesion).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"codigo\":\"" + codigo + "\"}")).andReturn();
    }

    private MockHttpServletRequestBuilder postJson(String ruta, MockHttpSession sesion, String cuerpo) {
        return post(ruta).session(sesion).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(cuerpo);
    }

    private static String cuerpoDe(MvcResult resultado) throws Exception {
        return resultado.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private int estado(MockHttpSession sesion, String ruta) throws Exception {
        return mockMvc.perform(get(ruta).session(sesion)).andReturn().getResponse().getStatus();
    }

    /** Activa el 2FA por la API, como lo haria la persona, y devuelve lo que necesita la prueba para entrar despues. */
    private CuentaConDosFactores conDosFactores() throws Exception {
        Cuenta cuenta = crearCuenta();
        MvcResult acceso = login(cuenta, CONTRASENA);
        MockHttpSession sesion = sesionDe(acceso);
        MvcResult inicio = mockMvc.perform(postJson("/api/2fa/activacion", sesion,
                "{\"contrasena\":\"" + CONTRASENA + "\"}")).andReturn();
        byte[] secreto = Base32.decodificar(JsonPath.read(cuerpoDe(inicio), "$.secreto"));
        long paso = totp.pasoActual();
        MvcResult confirmacion = mockMvc.perform(postJson("/api/2fa/activacion/confirmar", sesion,
                "{\"codigo\":\"" + totp.codigo(secreto, paso) + "\"}")).andReturn();
        assertThat(confirmacion.getResponse().getStatus()).as(cuerpoDe(confirmacion)).isEqualTo(200);
        List<String> codigos = JsonPath.read(cuerpoDe(confirmacion), "$.codigosRecuperacion");
        return new CuentaConDosFactores(cuenta, secreto, codigos, paso);
    }

    /** El codigo del paso siguiente al ultimo aceptado: el unico TOTP valido que queda dentro de la ventana de 30 s. */
    private String codigoSiguiente(CuentaConDosFactores cuenta) {
        return totp.codigo(cuenta.secreto(), cuenta.ultimoPaso() + 1);
    }

    private Document dosFactoresDe(Cuenta cuenta) {
        return usuarios.find(Filters.eq("_id", cuenta.id())).first().get("dosFactores", Document.class);
    }

    // ------------------------------------------------------------------ CP-2FA-363 a CP-2FA-365

    @Test
    void conDosFactores_laContrasenaSolaNoDaSesion_yUnCodigoCorrectoSi() throws Exception { // CP-2FA-363
        CuentaConDosFactores cuenta = conDosFactores();

        // Paso 1: la contrasena es correcta pero falta el codigo
        MvcResult primerPaso = login(cuenta.cuenta(), CONTRASENA);
        assertThat(primerPaso.getResponse().getStatus()).isEqualTo(200);
        assertThat((Boolean) JsonPath.read(cuerpoDe(primerPaso), "$.requiereSegundoFactor")).isTrue();
        assertThat(cuerpoDe(primerPaso)).doesNotContain(cuenta.cuenta().email(), cuenta.cuenta().id().toHexString());
        MockHttpSession pendiente = sesionDe(primerPaso);
        // Con esa sesion no se puede entrar a nada
        assertThat(estado(pendiente, "/api/auth/me")).isEqualTo(401);
        assertThat(estado(pendiente, "/api/2fa")).isEqualTo(401);
        assertThat(estado(pendiente, "/api/carrito")).isEqualTo(401);

        // Paso 2: el codigo del movil
        MvcResult segundoPaso = verificar(pendiente, codigoSiguiente(cuenta));
        assertThat(segundoPaso.getResponse().getStatus()).as(cuerpoDe(segundoPaso)).isEqualTo(200);
        assertThat((String) JsonPath.read(cuerpoDe(segundoPaso), "$.email")).isEqualTo(cuenta.cuenta().email());
        MockHttpSession definitiva = sesionDe(segundoPaso);
        assertThat(definitiva).isNotSameAs(pendiente);
        assertThat(estado(definitiva, "/api/auth/me")).isEqualTo(200);
        assertThat(estado(definitiva, "/api/2fa")).isEqualTo(200);
        // y la sesion pendiente ya no sirve
        assertThat(estado(pendiente, "/api/auth/me")).isEqualTo(401);
        assertThat(((Number) dosFactoresDe(cuenta.cuenta()).get("ultimoPaso")).longValue())
                .isEqualTo(cuenta.ultimoPaso() + 1);
    }

    @Test
    void sinDosFactores_elLoginSigueIgual_tambienConUnaActivacionPendiente() throws Exception { // CP-2FA-364
        Cuenta sinNada = crearCuenta();
        Cuenta conPendiente = crearCuenta();
        MockHttpSession sesion = sesionDe(login(conPendiente, CONTRASENA));
        mockMvc.perform(postJson("/api/2fa/activacion", sesion, "{\"contrasena\":\"" + CONTRASENA + "\"}"));

        for (Cuenta cuenta : List.of(sinNada, conPendiente)) {
            MvcResult resultado = login(cuenta, CONTRASENA);

            assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
            assertThat((String) JsonPath.read(cuerpoDe(resultado), "$.email")).isEqualTo(cuenta.email());
            assertThat(cuerpoDe(resultado)).doesNotContain("requiereSegundoFactor");
            assertThat(estado(sesionDe(resultado), "/api/auth/me")).isEqualTo(200);
        }
    }

    @Test
    void unCodigoTotpNoVale2Veces_enElLogin() throws Exception { // CP-2FA-365
        CuentaConDosFactores cuenta = conDosFactores();
        String codigo = codigoSiguiente(cuenta);
        MockHttpSession primera = sesionDe(login(cuenta.cuenta(), CONTRASENA));
        assertThat(verificar(primera, codigo).getResponse().getStatus()).isEqualTo(200);

        // El mismo codigo en un segundo login (otra sesion pendiente): rechazado
        MockHttpSession segunda = sesionDe(login(cuenta.cuenta(), CONTRASENA));
        MvcResult repetido = verificar(segunda, codigo);

        assertThat(repetido.getResponse().getStatus()).isEqualTo(400);
        assertThat((String) JsonPath.read(cuerpoDe(repetido), "$.errores.codigo[0]")).isEqualTo("CODIGO_INCORRECTO");
        assertThat(estado(segunda, "/api/auth/me")).isEqualTo(401);
    }

    // ------------------------------------------------------------------ CP-2FA-366

    @Test
    void conUnCodigoDeRecuperacion_seEntraYElCodigoQuedaConsumido() throws Exception { // CP-2FA-366
        CuentaConDosFactores cuenta = conDosFactores();
        String usado = cuenta.codigosRecuperacion().get(0);

        // Escrito como lo teclearia alguien: sin guiones y en minusculas
        MvcResult entrada = verificar(sesionDe(login(cuenta.cuenta(), CONTRASENA)), usado.replace("-", "").toLowerCase());

        assertThat(entrada.getResponse().getStatus()).isEqualTo(200);
        assertThat(estado(sesionDe(entrada), "/api/auth/me")).isEqualTo(200);
        List<Document> guardados = dosFactoresDe(cuenta.cuenta()).getList("codigosRecuperacion", Document.class);
        assertThat(guardados).filteredOn(c -> c.getBoolean("usado")).hasSize(1);
        // Reutilizarlo falla; con otro codigo si se entra
        MvcResult repetido = verificar(sesionDe(login(cuenta.cuenta(), CONTRASENA)), usado);
        assertThat(repetido.getResponse().getStatus()).isEqualTo(400);
        MvcResult otro = verificar(sesionDe(login(cuenta.cuenta(), CONTRASENA)), cuenta.codigosRecuperacion().get(1));
        assertThat(otro.getResponse().getStatus()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ CP-2FA-367 y CP-2FA-368

    @Test
    void conTresCodigosIncorrectos_elCuartoDa429AunqueSeaCorrecto_yNoAfectaAOtrasCuentas() throws Exception { // CP-2FA-367
        CuentaConDosFactores cuenta = conDosFactores();
        CuentaConDosFactores otra = conDosFactores();
        MockHttpSession pendiente = sesionDe(login(cuenta.cuenta(), CONTRASENA));

        for (int i = 1; i <= 3; i++) {
            MvcResult fallo = verificar(pendiente, CODIGO_MALO);
            assertThat(fallo.getResponse().getStatus()).as("intento %d", i).isEqualTo(400);
            assertThat((String) JsonPath.read(cuerpoDe(fallo), "$.errores.codigo[0]")).isEqualTo("CODIGO_INCORRECTO");
        }
        MvcResult bloqueado = verificar(pendiente, codigoSiguiente(cuenta));

        assertThat(bloqueado.getResponse().getStatus()).isEqualTo(429);
        assertThat(cuerpoDe(bloqueado)).doesNotContain(CODIGO_MALO);
        assertThat(estado(pendiente, "/api/auth/me")).isEqualTo(401);
        // El limite es de la cuenta: un login nuevo sigue pidiendo el codigo y sigue bloqueado; otra cuenta entra
        MockHttpSession otraVez = sesionDe(login(cuenta.cuenta(), CONTRASENA));
        assertThat(verificar(otraVez, codigoSiguiente(cuenta)).getResponse().getStatus()).isEqualTo(429);
        assertThat(verificar(sesionDe(login(otra.cuenta(), CONTRASENA)), codigoSiguiente(otra)).getResponse().getStatus())
                .isEqualTo(200);
    }

    @Test
    void losFallosDelCodigoNoBloqueanLaContrasena_ElLimiteDelLoginEsOtro() throws Exception { // CP-2FA-368
        CuentaConDosFactores cuenta = conDosFactores();
        MockHttpSession pendiente = sesionDe(login(cuenta.cuenta(), CONTRASENA));
        for (int i = 0; i < 3; i++) {
            verificar(pendiente, CODIGO_MALO);
        }

        // Una contrasena mala sigue siendo el 401 de siempre (no un 429), y la buena sigue pidiendo el segundo factor
        MvcResult mala = login(cuenta.cuenta(), "Otra-Contrasena-99!");
        MvcResult buena = login(cuenta.cuenta(), CONTRASENA);

        assertThat(mala.getResponse().getStatus()).isEqualTo(401);
        assertThat(buena.getResponse().getStatus()).isEqualTo(200);
        assertThat((Boolean) JsonPath.read(cuerpoDe(buena), "$.requiereSegundoFactor")).isTrue();
    }

    // ------------------------------------------------------------------ CP-2FA-369 (restablecer la contrasena)

    @Test
    void restablecerLaContrasena_noDesactivaNiSaltaElDosFactores() throws Exception { // CP-2FA-369
        CuentaConDosFactores cuenta = conDosFactores();
        Document antes = dosFactoresDe(cuenta.cuenta());
        String token = tokens.emitir(cuenta.cuenta().id().toHexString()).token();

        MvcResult restablecimiento = mockMvc.perform(post("/api/auth/recuperacion/restablecer").with(csrf())
                .with(desdeUnaIpNueva()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\",\"contrasena\":\"" + CONTRASENA_NUEVA + "\"}")).andReturn();

        assertThat(restablecimiento.getResponse().getStatus()).as(cuerpoDe(restablecimiento)).isEqualTo(200);
        // El 2FA queda exactamente como estaba
        assertThat(dosFactoresDe(cuenta.cuenta())).isEqualTo(antes);
        // Quien controla el correo cambio la contrasena, pero con ella sola no entra: el login pide el codigo
        MvcResult primerPaso = login(cuenta.cuenta(), CONTRASENA_NUEVA);
        assertThat((Boolean) JsonPath.read(cuerpoDe(primerPaso), "$.requiereSegundoFactor")).isTrue();
        MockHttpSession pendiente = sesionDe(primerPaso);
        assertThat(estado(pendiente, "/api/auth/me")).isEqualTo(401);
        // Sin el codigo, nada; con el codigo del movil, si
        assertThat(verificar(pendiente, CODIGO_MALO).getResponse().getStatus()).isEqualTo(400);
        assertThat(verificar(pendiente, codigoSiguiente(cuenta)).getResponse().getStatus()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ CP-2FA-370 a CP-2FA-373

    @Test
    void unaCuentaDesactivadaConDosFactores_recibeElMismoRechazoQueConUnaContrasenaMala() throws Exception { // CP-2FA-370
        CuentaConDosFactores cuenta = conDosFactores();
        usuarios.updateOne(Filters.eq("_id", cuenta.cuenta().id()), Updates.set("estado", "DESACTIVADO"));

        MvcResult desactivada = login(cuenta.cuenta(), CONTRASENA);
        MvcResult contrasenaMala = login(cuenta.cuenta(), "Otra-Contrasena-99!");

        // No se revela que tiene 2FA ni que la contrasena era buena
        assertThat(desactivada.getResponse().getStatus()).isEqualTo(401);
        assertThat(cuerpoDe(desactivada)).isEqualTo(cuerpoDe(contrasenaMala)).doesNotContain("requiereSegundoFactor");
    }

    @Test
    void siLaCuentaSeBloqueaEntreLosDosPasos_elCodigoCorrectoNoDaSesion() throws Exception { // CP-2FA-371
        CuentaConDosFactores cuenta = conDosFactores();
        MockHttpSession pendiente = sesionDe(login(cuenta.cuenta(), CONTRASENA));
        usuarios.updateOne(Filters.eq("_id", cuenta.cuenta().id()), Updates.set("estado", "BLOQUEADO"));

        MvcResult resultado = verificar(pendiente, codigoSiguiente(cuenta));

        assertThat(resultado.getResponse().getStatus()).isEqualTo(401);
        assertThat(estado(pendiente, "/api/auth/me")).isEqualTo(401);
        // y el desafio se descarta: ni siquiera con otro codigo
        assertThat(verificar(pendiente, cuenta.codigosRecuperacion().get(0)).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void siElDosFactoresSeQuitaEntreLosDosPasos_elDesafioYaNoEsVigente() throws Exception { // CP-2FA-372
        CuentaConDosFactores cuenta = conDosFactores();
        MockHttpSession pendiente = sesionDe(login(cuenta.cuenta(), CONTRASENA));
        usuarios.updateOne(Filters.eq("_id", cuenta.cuenta().id()), Updates.unset("dosFactores"));

        assertThat(verificar(pendiente, codigoSiguiente(cuenta)).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void elDesafioDeUnaSesionNoSirveEnOtra() throws Exception { // CP-2FA-373
        CuentaConDosFactores cuenta = conDosFactores();
        login(cuenta.cuenta(), CONTRASENA); // la sesion pendiente de Ana
        MockHttpSession deOtro = new MockHttpSession();

        MvcResult resultado = verificar(deOtro, codigoSiguiente(cuenta));

        assertThat(resultado.getResponse().getStatus()).isEqualTo(401);
    }

    // ------------------------------------------------------------------ CP-2FA-374 (concurrencia)

    @Test
    void dosPeticionesSimultaneasConElMismoCodigo_soloUnaEntra() throws Exception { // CP-2FA-374
        CuentaConDosFactores cuenta = conDosFactores();
        String codigo = codigoSiguiente(cuenta);
        List<MockHttpSession> pendientes = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            pendientes.add(sesionDe(login(cuenta.cuenta(), CONTRASENA)));
        }
        ExecutorService ejecutor = Executors.newFixedThreadPool(pendientes.size());
        List<Integer> estados = new ArrayList<>();
        try {
            CyclicBarrier barrera = new CyclicBarrier(pendientes.size());
            List<Future<Integer>> futuros = new ArrayList<>();
            for (MockHttpSession pendiente : pendientes) {
                Callable<Integer> tarea = () -> {
                    barrera.await(10, TimeUnit.SECONDS);
                    return verificar(pendiente, codigo).getResponse().getStatus();
                };
                futuros.add(ejecutor.submit(tarea));
            }
            for (Future<Integer> futuro : futuros) {
                estados.add(futuro.get(30, TimeUnit.SECONDS));
            }
        } finally {
            ejecutor.shutdownNow();
        }

        // El registro atomico del paso deja pasar una sola; las demas reciben el codigo incorrecto (o, si los fallos de
        // las otras ya agotaron el limite antes de que comprobaran, el 429)
        assertThat(estados).filteredOn(estado -> estado == 200).hasSize(1);
        assertThat(estados).filteredOn(estado -> estado != 200).hasSize(3).allMatch(estado -> estado == 400 || estado == 429);
    }

    // ------------------------------------------------------------------ CP-2FA-375 / 376

    @Test
    void elCodigoDelLoginTambienGastaElPasoParaRegenerarLosCodigos() throws Exception { // CP-2FA-375
        CuentaConDosFactores cuenta = conDosFactores();
        String codigo = codigoSiguiente(cuenta);
        MockHttpSession definitiva = sesionDe(verificar(sesionDe(login(cuenta.cuenta(), CONTRASENA)), codigo));

        // El mismo TOTP, ahora para regenerar los codigos con la sesion ya abierta: el paso esta gastado
        MvcResult regenerar = mockMvc.perform(postJson("/api/2fa/codigos-recuperacion", definitiva,
                "{\"contrasena\":\"" + CONTRASENA + "\",\"codigo\":\"" + codigo + "\"}")).andReturn();

        assertThat(regenerar.getResponse().getStatus()).isEqualTo(400);
        assertThat(dosFactoresDe(cuenta.cuenta()).getBoolean("activo")).isTrue();
    }

    @Test
    void ningunLogDeLaAplicacionContieneContrasenasCodigosNiSecretos() throws Exception { // CP-2FA-376
        List<String> sensibles = new ArrayList<>(List.of(CONTRASENA, CONTRASENA_NUEVA, "Otra-Contrasena-99!"));
        CuentaConDosFactores cuenta = conDosFactores();
        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            sensibles.add(Base32.codificar(cuenta.secreto()));
            sensibles.addAll(cuenta.codigosRecuperacion());
            sensibles.addAll(cuenta.codigosRecuperacion().stream().map(c -> c.replace("-", "")).toList());
            String bueno = codigoSiguiente(cuenta);
            sensibles.add(bueno);

            MockHttpSession pendiente = sesionDe(login(cuenta.cuenta(), CONTRASENA));
            verificar(pendiente, CODIGO_MALO);
            verificar(pendiente, bueno);
            verificar(sesionDe(login(cuenta.cuenta(), CONTRASENA)), cuenta.codigosRecuperacion().get(0));
            login(cuenta.cuenta(), "Otra-Contrasena-99!");

            // Solo los logs de la aplicacion: a TRACE el driver de MongoDB escribe las consultas con los datos del usuario
            List<String> deLaAplicacion = logs.eventos().stream()
                    .filter(evento -> evento.getLoggerName().startsWith("com.esibuy"))
                    .map(evento -> evento.getFormattedMessage()
                            + (evento.getThrowableProxy() == null ? "" : evento.getThrowableProxy().getMessage()))
                    .toList();
            assertThat(deLaAplicacion).isNotEmpty().allSatisfy(texto -> sensibles.forEach(
                    sensible -> assertThat(texto).doesNotContain(sensible)));
            // y se ven los eventos del flujo: primer paso, login correcto con el segundo factor y fallos
            assertThat(String.join("\n", deLaAplicacion)).contains("LOGIN_PRIMER_FACTOR_CORRECTO", "LOGIN_CORRECTO",
                    "DOS_FACTORES_FALLIDO");
        }
    }
}
