package com.esibuy.esibuy_backend.integracion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.esibuy.esibuy_backend.servicio.Base32;
import com.esibuy.esibuy_backend.servicio.CifradorSecretos;
import com.esibuy.esibuy_backend.servicio.DiccionarioContrasenasProhibidas;
import com.esibuy.esibuy_backend.servicio.GeneradorTotp;
import com.esibuy.esibuy_backend.servicio.ValidadorDominioEmail;
import com.esibuy.esibuy_backend.util.CapturadorLogs;
import com.esibuy.esibuy_backend.util.ConstructorSolicitudCliente;
import com.esibuy.esibuy_backend.util.ConstructorSolicitudVendedor;
import com.jayway.jsonpath.JsonPath;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;

import tools.jackson.databind.ObjectMapper;

/**
 * Registro con 2FA de punta a punta, con la seguridad real, MongoDB real (con los validadores del esquema) y la sesion real
 * (HdU 2FA en el registro, fase B). Casos: CP-2FA-505 a CP-2FA-520.
 *
 * Los codigos TOTP los calcula la prueba con el mismo generador que el servidor a partir del secreto del QR, como la aplicacion
 * autenticadora. Lo mas importante que fijan:
 *  - Preparar el QR, escanearlo y registrarse crea la cuenta (DESACTIVADA, como siempre) con el 2FA ya ACTIVO, de una vez: el
 *    documento cumple el esquema, el secreto esta cifrado con el id de la cuenta, solo se guardan los hashes de los codigos de
 *    recuperacion y nada en claro llega a la base de datos.
 *  - La cuenta recien registrada entra con el login en dos pasos: con un codigo de recuperacion de los que se mostraron en el
 *    registro, o con el TOTP del paso siguiente al que se uso al registrarse (el mismo codigo no vale dos veces).
 *  - Sin un codigo correcto no hay cuenta: ni con codigo incorrecto, ni con un correo distinto del escaneado, ni desde otra
 *    sesion, ni sin token CSRF, ni reutilizando un secreto ya usado, ni tras agotar los 5 intentos.
 *  - Un codigo incorrecto no revela si el correo esta registrado; con codigo correcto, un correo repetido es el 409 generico.
 *  - Dos registros simultaneos con la misma sesion y el mismo correo: solo se crea una cuenta.
 *  - Ni el secreto, ni el codigo, ni los codigos de recuperacion, ni la contrasena aparecen en los logs de la aplicacion.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class RegistroDosFactoresIntegracionMongoTest extends IntegracionLoginBase {

    private static final String PREFIJO_EMAIL = "reg2fa-final-";
    private static final String CODIGO_MALO = "000000";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private MongoTemplate mongo;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private GeneradorTotp totp;
    @Autowired
    private CifradorSecretos cifrador;
    @MockitoBean
    private ValidadorDominioEmail validadorDominioEmail;
    @MockitoBean
    private DiccionarioContrasenasProhibidas diccionario;

    private MongoCollection<Document> usuarios;
    private static final AtomicInteger contadorIp = new AtomicInteger(1);

    @BeforeEach
    void prepararColecciones() {
        usuarios = mongo.getCollection("usuarios");
        when(validadorDominioEmail.tieneDominioValido(anyString())).thenReturn(true);
    }

    @AfterEach
    void limpiar() {
        usuarios.deleteMany(Filters.regex("email", "^" + PREFIJO_EMAIL));
    }

    // ------------------------------------------------------------------ utilidades

    /** Lo que la persona tiene tras escanear el QR: la sesion con el secreto pendiente y el secreto de su aplicacion. */
    private record QrEscaneado(MockHttpSession sesion, String correo, byte[] secreto) {
    }

    private record CuentaRegistrada(String correo, byte[] secreto, List<String> codigosRecuperacion, long paso, ObjectId id) {
    }

    private static String correoNuevo() {
        return PREFIJO_EMAIL + UUID.randomUUID() + "@ejemplo.es";
    }

    private static RequestPostProcessor desdeUnaIpNueva() {
        String ip = "198.51.100." + contadorIp.getAndIncrement();
        return peticion -> {
            peticion.setRemoteAddr(ip);
            return peticion;
        };
    }

    private QrEscaneado escanearQr(String correo) throws Exception {
        MockHttpSession sesion = new MockHttpSession();
        MvcResult resultado = mockMvc.perform(post("/api/auth/registro/2fa").session(sesion).with(csrf())
                .with(desdeUnaIpNueva()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + correo + "\"}")).andReturn();
        assertThat(resultado.getResponse().getStatus()).as(cuerpoDe(resultado)).isEqualTo(200);
        return new QrEscaneado(sesion, correo.toLowerCase(), Base32.decodificar(JsonPath.read(cuerpoDe(resultado), "$.secreto")));
    }

    private String codigoActual(QrEscaneado qr) {
        return totp.codigo(qr.secreto(), totp.pasoActual());
    }

    private MvcResult registrarCliente(MockHttpSession sesion, String correo, String codigo, boolean conCsrf) throws Exception {
        String cuerpo = objectMapper.writeValueAsString(ConstructorSolicitudCliente.unaSolicitudValida()
                .conEmail(correo).conCodigoDosFactores(codigo).construir());
        var peticion = post("/api/auth/registro").session(sesion).with(desdeUnaIpNueva())
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo);
        return mockMvc.perform(conCsrf ? peticion.with(csrf()) : peticion).andReturn();
    }

    private MvcResult registrarCliente(QrEscaneado qr, String codigo) throws Exception {
        return registrarCliente(qr.sesion(), qr.correo(), codigo, true);
    }

    private CuentaRegistrada cuentaRegistrada() throws Exception {
        QrEscaneado qr = escanearQr(correoNuevo());
        long paso = totp.pasoActual();
        MvcResult resultado = registrarCliente(qr, totp.codigo(qr.secreto(), paso));
        assertThat(resultado.getResponse().getStatus()).as(cuerpoDe(resultado)).isEqualTo(201);
        List<String> codigos = JsonPath.read(cuerpoDe(resultado), "$.codigosRecuperacion");
        return new CuentaRegistrada(qr.correo(), qr.secreto(), codigos, paso, usuarioPorCorreo(qr.correo()).getObjectId("_id"));
    }

    private Document usuarioPorCorreo(String correo) {
        return usuarios.find(Filters.eq("email", correo)).first();
    }

    private long cuentasCon(String correo) {
        return usuarios.countDocuments(Filters.eq("email", correo));
    }

    private static String cuerpoDe(MvcResult resultado) throws Exception {
        return resultado.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static String erroresDelCodigo(MvcResult resultado) throws Exception {
        return JsonPath.read(cuerpoDe(resultado), "$.errores.codigoDosFactores[0]");
    }

    private void activarLaCuenta(CuentaRegistrada cuenta) {
        usuarios.updateOne(Filters.eq("_id", cuenta.id()), Updates.set("estado", "ACTIVO"));
    }

    private MvcResult login(CuentaRegistrada cuenta) throws Exception {
        return mockMvc.perform(post("/api/auth/login").with(csrf()).with(desdeUnaIpNueva())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + cuenta.correo() + "\",\"contrasena\":\"" + ConstructorSolicitudCliente.CONTRASENA_VALIDA
                        + "\"}")).andReturn();
    }

    private MvcResult verificar(MockHttpSession sesion, String codigo) throws Exception {
        return mockMvc.perform(post("/api/auth/2fa/verificar").session(sesion).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"codigo\":\"" + codigo + "\"}")).andReturn();
    }

    private int estado(MockHttpSession sesion, String ruta) throws Exception {
        return mockMvc.perform(get(ruta).session(sesion)).andReturn().getResponse().getStatus();
    }

    // ------------------------------------------------------------------ la cuenta que se crea

    @Test
    void registrar_creaLaCuentaDesactivadaConElDosFactoresActivoYDevuelveLosCodigosDeRecuperacion() throws Exception { // CP-2FA-505
        QrEscaneado qr = escanearQr(correoNuevo());
        long paso = totp.pasoActual();

        MvcResult resultado = registrarCliente(qr, totp.codigo(qr.secreto(), paso));

        assertThat(resultado.getResponse().getStatus()).as(cuerpoDe(resultado)).isEqualTo(201);
        assertThat((String) JsonPath.read(cuerpoDe(resultado), "$.email")).isEqualTo(qr.correo());
        List<String> codigos = JsonPath.read(cuerpoDe(resultado), "$.codigosRecuperacion");
        assertThat(codigos).hasSize(10).doesNotHaveDuplicates()
                .allSatisfy(codigo -> assertThat(codigo).matches("^[A-Z2-7]{4}(-[A-Z2-7]{4}){3}$"));
        assertThat(cuerpoDe(resultado)).doesNotContain(Base32.codificar(qr.secreto()));

        Document usuario = usuarioPorCorreo(qr.correo());
        assertThat(usuario.getString("estado")).isEqualTo("DESACTIVADO");
        Document dosFactores = usuario.get("dosFactores", Document.class);
        assertThat(dosFactores.getBoolean("activo")).isTrue();
        assertThat(dosFactores.get("pendiente")).isNull();
        assertThat(dosFactores.get("activadoEn")).isNotNull();
        assertThat(((Number) dosFactores.get("ultimoPaso")).longValue()).isEqualTo(paso);
        List<Document> guardados = dosFactores.getList("codigosRecuperacion", Document.class);
        assertThat(guardados).hasSize(10).noneMatch(codigo -> codigo.getBoolean("usado"))
                .allSatisfy(codigo -> assertThat(codigo.getString("hash")).matches("^[0-9a-f]{64}$"));
    }

    @Test
    void registrar_elSecretoQuedaCifradoConElIdDeLaCuentaYNadaEnClaroLlegaALaBaseDeDatos() throws Exception { // CP-2FA-506
        QrEscaneado qr = escanearQr(correoNuevo());

        MvcResult resultado = registrarCliente(qr, codigoActual(qr));

        assertThat(resultado.getResponse().getStatus()).as(cuerpoDe(resultado)).isEqualTo(201);
        Document usuario = usuarioPorCorreo(qr.correo());
        String cifrado = usuario.get("dosFactores", Document.class).getString("secretoCifrado");
        assertThat(cifrado).matches("^1:[A-Za-z0-9_-]+$");
        assertThat(cifrador.descifrar(cifrado, usuario.getObjectId("_id").toHexString())).isEqualTo(qr.secreto());
        // No es el texto ligado al correo que habia en la sesion
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> cifrador.descifrar(cifrado, qr.correo()));
        List<String> codigos = JsonPath.read(cuerpoDe(resultado), "$.codigosRecuperacion");
        String documento = usuario.toJson();
        assertThat(documento).doesNotContain(Base32.codificar(qr.secreto()), ConstructorSolicitudCliente.CONTRASENA_VALIDA);
        codigos.forEach(codigo -> assertThat(documento).doesNotContain(codigo, codigo.replace("-", "")));
    }

    @Test
    void registrar_elCorreoSeGuardaNormalizadoAunqueSeEscribaConMayusculas() throws Exception { // CP-2FA-507
        String correo = correoNuevo();
        QrEscaneado qr = escanearQr(correo.toUpperCase());

        MvcResult resultado = registrarCliente(qr.sesion(), "  " + correo.toUpperCase() + " ", codigoActual(qr), true);

        assertThat(resultado.getResponse().getStatus()).as(cuerpoDe(resultado)).isEqualTo(201);
        assertThat(cuentasCon(correo)).isEqualTo(1);
    }

    @Test
    void registrarVendedor_tambienCreaLaCuentaConElDosFactoresActivo() throws Exception { // CP-2FA-508
        String correo = correoNuevo();
        ObjectId categoria = new ObjectId(ConstructorSolicitudVendedor.CATEGORIA_VALIDA);
        mongo.getCollection("categorias").replaceOne(Filters.eq("_id", categoria),
                new Document("_id", categoria).append("nombre", "Electronica"),
                new com.mongodb.client.model.ReplaceOptions().upsert(true));
        QrEscaneado qr = escanearQr(correo);
        String cuerpo = objectMapper.writeValueAsString(ConstructorSolicitudVendedor.unaSolicitudValida().conEmail(correo)
                .conNombreComercial("Tienda " + UUID.randomUUID()).conCodigoDosFactores(codigoActual(qr)).construir());

        MvcResult resultado = mockMvc.perform(post("/api/auth/registro").session(qr.sesion()).with(csrf()).with(desdeUnaIpNueva())
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo)).andReturn();

        assertThat(resultado.getResponse().getStatus()).as(cuerpoDe(resultado)).isEqualTo(201);
        Document usuario = usuarioPorCorreo(correo);
        assertThat(usuario.getList("rol", String.class)).containsExactly("VENDEDOR");
        assertThat(usuario.get("dosFactores", Document.class).getBoolean("activo")).isTrue();
        assertThat((List<?>) JsonPath.read(cuerpoDe(resultado), "$.codigosRecuperacion")).hasSize(10);
    }

    // ------------------------------------------------------------------ la cuenta entra con el login en dos pasos

    @Test
    void laCuentaRecienRegistrada_entraConUnCodigoDeRecuperacionDeLosQueSeMostraron() throws Exception { // CP-2FA-509
        CuentaRegistrada cuenta = cuentaRegistrada();
        activarLaCuenta(cuenta);

        MvcResult primerPaso = login(cuenta);
        assertThat(primerPaso.getResponse().getStatus()).isEqualTo(200);
        assertThat((Boolean) JsonPath.read(cuerpoDe(primerPaso), "$.requiereSegundoFactor")).isTrue();
        MockHttpSession pendiente = (MockHttpSession) primerPaso.getRequest().getSession(false);
        MvcResult segundoPaso = verificar(pendiente, cuenta.codigosRecuperacion().get(0));

        assertThat(segundoPaso.getResponse().getStatus()).as(cuerpoDe(segundoPaso)).isEqualTo(200);
        MockHttpSession sesion = (MockHttpSession) segundoPaso.getRequest().getSession(false);
        assertThat(estado(sesion, "/api/auth/me")).isEqualTo(200);
    }

    @Test
    void laCuentaRecienRegistrada_noPuedeEntrarConElMismoCodigoTotpPeroSiConElDelPasoSiguiente() throws Exception { // CP-2FA-510
        CuentaRegistrada cuenta = cuentaRegistrada();
        activarLaCuenta(cuenta);
        MockHttpSession primera = (MockHttpSession) login(cuenta).getRequest().getSession(false);

        // El codigo del registro ya se uso (es el ultimo paso aceptado de la cuenta)
        MvcResult repetido = verificar(primera, totp.codigo(cuenta.secreto(), cuenta.paso()));
        assertThat(repetido.getResponse().getStatus()).isEqualTo(400);
        assertThat((String) JsonPath.read(cuerpoDe(repetido), "$.errores.codigo[0]")).isEqualTo("CODIGO_INCORRECTO");

        MockHttpSession segunda = (MockHttpSession) login(cuenta).getRequest().getSession(false);
        MvcResult siguiente = verificar(segunda, totp.codigo(cuenta.secreto(), cuenta.paso() + 1));
        assertThat(siguiente.getResponse().getStatus()).as(cuerpoDe(siguiente)).isEqualTo(200);
    }

    // ------------------------------------------------------------------ sin un codigo correcto no hay cuenta

    @Test
    void registrar_conUnCodigoIncorrecto_devuelve400YNoCreaLaCuenta() throws Exception { // CP-2FA-511
        QrEscaneado qr = escanearQr(correoNuevo());

        MvcResult resultado = registrarCliente(qr, CODIGO_MALO);

        assertThat(resultado.getResponse().getStatus()).isEqualTo(400);
        assertThat(erroresDelCodigo(resultado)).isEqualTo("CODIGO_INCORRECTO");
        assertThat(cuentasCon(qr.correo())).isZero();
        // El mismo secreto sigue valiendo para reintentar con el codigo bueno
        assertThat(registrarCliente(qr, codigoActual(qr)).getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void registrar_alAgotarLosCincoIntentos_elSecretoSeDescartaAunqueDespuesSeAcierte() throws Exception { // CP-2FA-512
        QrEscaneado qr = escanearQr(correoNuevo());

        for (int intento = 1; intento <= 4; intento++) {
            assertThat(erroresDelCodigo(registrarCliente(qr, CODIGO_MALO))).as("intento %d", intento).isEqualTo("CODIGO_INCORRECTO");
        }
        assertThat(erroresDelCodigo(registrarCliente(qr, CODIGO_MALO))).isEqualTo("ACTIVACION_NO_VIGENTE");

        MvcResult tarde = registrarCliente(qr, codigoActual(qr));
        assertThat(tarde.getResponse().getStatus()).isEqualTo(400);
        assertThat(erroresDelCodigo(tarde)).isEqualTo("ACTIVACION_NO_VIGENTE");
        assertThat(cuentasCon(qr.correo())).isZero();
    }

    @Test
    void registrar_conUnCorreoDistintoDelEscaneado_devuelveActivacionNoVigenteYNoCreaNada() throws Exception { // CP-2FA-513
        QrEscaneado qr = escanearQr(correoNuevo());
        String otro = correoNuevo();

        MvcResult resultado = registrarCliente(qr.sesion(), otro, codigoActual(qr), true);

        assertThat(resultado.getResponse().getStatus()).isEqualTo(400);
        assertThat(erroresDelCodigo(resultado)).isEqualTo("ACTIVACION_NO_VIGENTE");
        assertThat(cuentasCon(otro)).isZero();
        assertThat(cuentasCon(qr.correo())).isZero();
    }

    @Test
    void registrar_desdeOtraSesionOSinPreparar_devuelveActivacionNoVigente() throws Exception { // CP-2FA-514
        QrEscaneado qr = escanearQr(correoNuevo());

        MvcResult otraSesion = registrarCliente(new MockHttpSession(), qr.correo(), codigoActual(qr), true);
        MvcResult sinSesion = mockMvc.perform(post("/api/auth/registro").with(csrf()).with(desdeUnaIpNueva())
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(
                        ConstructorSolicitudCliente.unaSolicitudValida().conEmail(qr.correo())
                                .conCodigoDosFactores(codigoActual(qr)).construir()))).andReturn();

        for (MvcResult resultado : List.of(otraSesion, sinSesion)) {
            assertThat(resultado.getResponse().getStatus()).isEqualTo(400);
            assertThat(erroresDelCodigo(resultado)).isEqualTo("ACTIVACION_NO_VIGENTE");
        }
        assertThat(cuentasCon(qr.correo())).isZero();
    }

    @Test
    void registrar_sinCodigo_devuelveObligatorioYNoCreaLaCuenta() throws Exception { // CP-2FA-515
        QrEscaneado qr = escanearQr(correoNuevo());

        MvcResult resultado = registrarCliente(qr, null);

        assertThat(resultado.getResponse().getStatus()).isEqualTo(400);
        assertThat(erroresDelCodigo(resultado)).isEqualTo("OBLIGATORIO");
        assertThat(cuentasCon(qr.correo())).isZero();
    }

    @Test
    void registrar_sinTokenCsrf_devuelve403YNoCreaLaCuenta() throws Exception { // CP-2FA-516
        QrEscaneado qr = escanearQr(correoNuevo());

        MvcResult resultado = registrarCliente(qr.sesion(), qr.correo(), codigoActual(qr), false);

        assertThat(resultado.getResponse().getStatus()).isEqualTo(403);
        assertThat(cuentasCon(qr.correo())).isZero();
    }

    @Test
    void registrar_unSecretoSoloSirveParaUnaCuenta() throws Exception { // CP-2FA-517
        QrEscaneado qr = escanearQr(correoNuevo());
        assertThat(registrarCliente(qr, codigoActual(qr)).getResponse().getStatus()).isEqualTo(201);

        // Con la misma sesion y el mismo correo (o con otro): el pendiente ya se consumio
        MvcResult repetido = registrarCliente(qr, codigoActual(qr));
        MvcResult conOtroCorreo = registrarCliente(qr.sesion(), correoNuevo(), codigoActual(qr), true);

        for (MvcResult resultado : List.of(repetido, conOtroCorreo)) {
            assertThat(resultado.getResponse().getStatus()).isEqualTo(400);
            assertThat(erroresDelCodigo(resultado)).isEqualTo("ACTIVACION_NO_VIGENTE");
        }
        assertThat(cuentasCon(qr.correo())).isEqualTo(1);
    }

    // ------------------------------------------------------------------ correo repetido y carreras

    @Test
    void registrar_conUnCorreoYaRegistrado_conCodigoCorrectoEsElErrorGenericoYConCodigoIncorrectoNoLoRevela() throws Exception { // CP-2FA-518
        CuentaRegistrada existente = cuentaRegistrada();
        QrEscaneado qr = escanearQr(existente.correo());

        MvcResult conCodigoMalo = registrarCliente(qr, CODIGO_MALO);
        MvcResult conCodigoBueno = registrarCliente(qr, codigoActual(qr));

        // Con el codigo incorrecto es indistinguible de un correo libre
        assertThat(conCodigoMalo.getResponse().getStatus()).isEqualTo(400);
        assertThat(erroresDelCodigo(conCodigoMalo)).isEqualTo("CODIGO_INCORRECTO");
        // Con el correcto, la respuesta generica de siempre (409) y no se crea otra cuenta
        assertThat(conCodigoBueno.getResponse().getStatus()).isEqualTo(409);
        assertThat(cuentasCon(existente.correo())).isEqualTo(1);
    }

    @Test
    void registrar_dosPeticionesSimultaneasConLaMismaSesionYElMismoCorreo_creanUnaSolaCuenta() throws Exception { // CP-2FA-519
        QrEscaneado qr = escanearQr(correoNuevo());
        String codigo = codigoActual(qr);
        CyclicBarrier salida = new CyclicBarrier(2);
        ExecutorService hilos = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> resultados = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                resultados.add(hilos.submit(() -> {
                    salida.await(5, TimeUnit.SECONDS);
                    return registrarCliente(qr, codigo).getResponse().getStatus();
                }));
            }
            List<Integer> estados = new ArrayList<>();
            for (Future<Integer> resultado : resultados) {
                estados.add(resultado.get(30, TimeUnit.SECONDS));
            }

            // Uno crea la cuenta; el otro recibe el error generico (o, si llega despues, que el secreto ya no esta vigente)
            assertThat(estados).contains(201).hasSize(2).doesNotContain(500);
            assertThat(estados.stream().filter(estado -> estado == 201).count()).isEqualTo(1);
            assertThat(cuentasCon(qr.correo())).isEqualTo(1);
        } finally {
            hilos.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ logs

    @Test
    void ningunLogDeLaAplicacionContieneElSecretoElCodigoLosCodigosDeRecuperacionNiLaContrasena() throws Exception { // CP-2FA-520
        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            QrEscaneado qr = escanearQr(correoNuevo());
            String codigo = codigoActual(qr);
            registrarCliente(qr, CODIGO_MALO);
            MvcResult resultado = registrarCliente(qr, codigo);
            List<String> codigos = JsonPath.read(cuerpoDe(resultado), "$.codigosRecuperacion");

            // Solo los logs de la aplicacion: a TRACE el driver de MongoDB escribe las consultas con datos del usuario
            String texto = String.join("\n", logs.eventos().stream()
                    .filter(evento -> evento.getLoggerName().startsWith("com.esibuy"))
                    .map(evento -> evento.getFormattedMessage()
                            + (evento.getThrowableProxy() == null ? "" : evento.getThrowableProxy().getMessage()))
                    .toList());
            assertThat(texto).contains("REGISTRO_DOS_FACTORES_COMPLETADO", "REGISTRO_DOS_FACTORES_FALLIDO");
            assertThat(texto).doesNotContain(Base32.codificar(qr.secreto()), codigo, qr.correo(),
                    ConstructorSolicitudCliente.CONTRASENA_VALIDA);
            codigos.forEach(codigoRecuperacion -> assertThat(texto).doesNotContain(codigoRecuperacion,
                    codigoRecuperacion.replace("-", "")));
        }
    }
}
