package com.esibuy.esibuy_backend.integracion;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import org.bson.Document;
import org.bson.types.ObjectId;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.MountableFile;

import com.esibuy.esibuy_backend.dto.SolicitudRegistroClienteDTO;
import com.esibuy.esibuy_backend.dto.SolicitudRegistroVendedorDTO;
import com.esibuy.esibuy_backend.dto.TipoCuenta;
import com.esibuy.esibuy_backend.modelo.Rol;
import com.esibuy.esibuy_backend.servicio.DiccionarioContrasenasProhibidas;
import com.esibuy.esibuy_backend.servicio.ValidadorDominioEmail;
import com.esibuy.esibuy_backend.util.ConstructorSolicitudCliente;
import com.esibuy.esibuy_backend.util.ConstructorSolicitudVendedor;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;

import tools.jackson.databind.ObjectMapper;

/**
 * Pruebas de integracion contra un MongoDB real (contenedor) con el script de BBDD del proyecto
 * aplicado: validadores JSON Schema e indices incluidos. Es la defensa en profundidad que pide el
 * enunciado y la que Mockito no puede comprobar.
 *
 * Requisitos: Docker en marcha y el script copiado en src/test/resources/mongo/init-esibuy.js
 * (ver CONTRATO_PRODUCCION.md).
 *
 * Sin Docker la clase se OMITE (aparece como "skipped", no como fallo) para no bloquear a quien no lo tenga
 * instalado. Antes de cerrar una fase hay que ejecutarla con Docker y comprobar que no sale omitida.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class RegistroIntegracionMongoTest {

    // Un unico endpoint: el campo tipoCuenta del cuerpo decide si es cliente o vendedor
    private static final String RUTA_REGISTRO = "/api/auth/registro";
    private static final int CODIGO_ERROR_VALIDACION_ESQUEMA = 121;

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("mongo/init-esibuy.js"), "/tmp/init-esibuy.js");

    @DynamicPropertySource
    static void configurarPropiedades(DynamicPropertyRegistry registro) {
        registro.add("spring.mongodb.uri", () -> MONGO.getReplicaSetUrl("ESIBuy"));
        registro.add("esibuy.seguridad.pepper", () -> "pepper-de-integracion");
        // El limite de peticiones no debe interferir con estas pruebas
        registro.add("esibuy.limite-registro.max-peticiones", () -> "1000");
    }

    @BeforeAll
    static void aplicarElScriptDeBaseDeDatos() throws Exception {
        ExecResult resultado = MONGO.execInContainer("mongosh", "--quiet", "--file", "/tmp/init-esibuy.js");
        assertEquals(0, resultado.getExitCode(), "Fallo al ejecutar el script de BBDD: " + resultado.getStderr());
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
    private MongoCollection<Document> categorias;

    @BeforeEach
    void limpiarColeccionesYPrepararMocks() {
        usuarios = mongo.getCollection("usuarios");
        categorias = mongo.getCollection("categorias");
        usuarios.deleteMany(new Document());
        categorias.deleteMany(new Document());
        when(validadorDominioEmail.tieneDominioValido(anyString())).thenReturn(true);
    }

    // ------------------------------------------------------------------ utilidades

    private ResultActions registrarCliente(SolicitudRegistroClienteDTO solicitud) throws Exception {
        return mockMvc.perform(post(RUTA_REGISTRO)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(solicitud)));
    }

    private ResultActions registrarCliente(String email) throws Exception {
        return registrarCliente(ConstructorSolicitudCliente.unaSolicitudValida().conEmail(email).construir());
    }

    private ResultActions registrarVendedor(SolicitudRegistroVendedorDTO solicitud) throws Exception {
        return mockMvc.perform(post(RUTA_REGISTRO)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(solicitud)));
    }

    private Document usuarioPorEmail(String email) {
        return usuarios.find(Filters.eq("email", email)).first();
    }

    private Document perfilDe(Document usuario) {
        return usuario.get("perfil", Document.class);
    }

    /** Documento valido segun el esquema de la coleccion usuarios (para inserciones directas). */
    private static Document documentoCliente(String email) {
        return new Document("email", email)
                .append("password", "$argon2id$hash-de-prueba")
                .append("estado", "ACTIVO")
                .append("rol", List.of("CLIENTE"))
                .append("perfil", new Document("nombre", "Ana").append("apellidos", "Garc\u00eda"));
    }

    private static Document documentoVendedor(String email, String nombreComercial) {
        return new Document("email", email)
                .append("password", "$argon2id$hash-de-prueba")
                .append("estado", "ACTIVO")
                .append("rol", List.of("VENDEDOR"))
                .append("perfil", new Document("nombre", "Luis").append("apellidos", "P\u00e9rez")
                        .append("nombreComercial", nombreComercial)
                        .append("idCategoriaPrincipal", new ObjectId()));
    }

    // ------------------------------------------------------------------ CP-INT-01

    @Test
    void registrarCliente_extremoAExtremo_persisteDocumentoConHashUnRolYEstadoDesactivado() throws Exception { // CP-INT-01
        // Given
        SolicitudRegistroClienteDTO solicitud = ConstructorSolicitudCliente.unaSolicitudValida().construir();

        // When
        registrarCliente(solicitud).andExpect(status().isCreated());

        // Then
        Document usuario = usuarioPorEmail(ConstructorSolicitudCliente.EMAIL_POR_DEFECTO);
        assertThat(usuario).isNotNull();
        assertThat(usuario.getString("password")).startsWith("$argon2id$");
        // "password" es el campo del esquema que guarda el hash (comprobado arriba); la contrasena en claro
        // no aparece en ningun campo (comprobado abajo con toJson)
        assertThat(usuario.containsKey("contrasena")).isFalse();
        assertThat(usuario.containsKey("_class")).as("solo los campos del esquema").isFalse();
        assertThat(usuario.toJson()).doesNotContain(ConstructorSolicitudCliente.CONTRASENA_VALIDA);
        assertThat(usuario.getList("rol", String.class)).containsExactly("CLIENTE");
        assertThat(usuario.getString("estado")).isEqualTo("DESACTIVADO");
        assertThat(perfilDe(usuario).getString("nombre")).isEqualTo("Ana");
        assertThat(perfilDe(usuario).getString("apellidos")).isEqualTo("Garc\u00eda L\u00f3pez");
    }

    // ------------------------------------------------------------------ CP-INT-02

    @Test
    void registrarCliente_variosRegistrosSimultaneosConElMismoEmail_soloUnoSeGuarda() throws Exception { // CP-INT-02
        // Given: cuatro hilos que registran el mismo email a la vez
        int hilos = 4;
        ExecutorService ejecutor = Executors.newFixedThreadPool(hilos);
        CyclicBarrier salidaSimultanea = new CyclicBarrier(hilos);
        List<Future<Integer>> futuros = new ArrayList<>();

        // When
        for (int i = 0; i < hilos; i++) {
            futuros.add(ejecutor.submit(() -> {
                salidaSimultanea.await();
                return registrarCliente(ConstructorSolicitudCliente.EMAIL_POR_DEFECTO)
                        .andReturn().getResponse().getStatus();
            }));
        }
        List<Integer> estados = new ArrayList<>();
        for (Future<Integer> futuro : futuros) {
            estados.add(futuro.get(60, TimeUnit.SECONDS));
        }
        ejecutor.shutdown();

        // Then: exactamente un 201 y el resto 409, y un unico documento en la BBDD
        assertThat(estados.stream().filter(estado -> estado == 201).count()).isEqualTo(1);
        assertThat(estados.stream().filter(estado -> estado == 409).count()).isEqualTo(hilos - 1);
        assertThat(usuarios.countDocuments(Filters.eq("email", ConstructorSolicitudCliente.EMAIL_POR_DEFECTO)))
                .isEqualTo(1);
    }

    @Test
    void insertOne_dosUsuariosConElMismoEmail_elIndiceUnicoRechazaElSegundo() { // CP-INT-02
        // Given
        usuarios.insertOne(documentoCliente("repetido@ejemplo.es"));

        // When
        MongoWriteException excepcion = assertThrows(MongoWriteException.class,
                () -> usuarios.insertOne(documentoCliente("repetido@ejemplo.es")));

        // Then
        assertThat(excepcion.getError().getCategory()).isEqualTo(ErrorCategory.DUPLICATE_KEY);
    }

    // ------------------------------------------------------------------ CP-INT-03

    @Test
    void insertOne_dosVendedoresConElMismoNombreComercial_elIndiceParcialRechazaElSegundo() { // CP-INT-03
        // Given
        usuarios.insertOne(documentoVendedor("uno@tienda.es", "Tienda Norte"));

        // When
        MongoWriteException excepcion = assertThrows(MongoWriteException.class,
                () -> usuarios.insertOne(documentoVendedor("dos@tienda.es", "Tienda Norte")));

        // Then
        assertThat(excepcion.getError().getCategory()).isEqualTo(ErrorCategory.DUPLICATE_KEY);
    }

    @Test
    void insertOne_mismoNombreComercialConOtraCapitalizacion_elIndiceConCollationRechazaElSegundo() { // CP-INT-03
        // Given
        usuarios.insertOne(documentoVendedor("uno@tienda.es", "Tienda Norte"));

        // When
        MongoWriteException excepcion = assertThrows(MongoWriteException.class,
                () -> usuarios.insertOne(documentoVendedor("dos@tienda.es", "TIENDA NORTE")));

        // Then
        assertThat(excepcion.getError().getCategory()).isEqualTo(ErrorCategory.DUPLICATE_KEY);
    }

    @Test
    void registrarVendedor_nombreComercialExistenteConOtraCapitalizacion_devuelve400SinGuardar() throws Exception { // CP-REG-43
        // Given: ya existe "Tienda Norte" y la categoria del vendedor
        categorias.insertOne(new Document("_id", new ObjectId(ConstructorSolicitudVendedor.CATEGORIA_VALIDA))
                .append("nombre", "Electronica"));
        usuarios.insertOne(documentoVendedor("otro@tienda.es", "Tienda Norte"));
        SolicitudRegistroVendedorDTO solicitud = ConstructorSolicitudVendedor.unaSolicitudValida()
                .conNombreComercial("  TIENDA   norte ").construir();

        // When / Then: la consulta del repositorio con collation lo detecta antes de intentar guardar
        registrarVendedor(solicitud)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores.nombreComercial[0]").value("NOMBRE_COMERCIAL_DUPLICADO"));
        assertThat(usuarios.countDocuments()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ CP-INT-04

    @Test
    void registrarCliente_variosClientesSinNombreComercial_todosSeGuardanSinCampoNombreComercial() throws Exception { // CP-INT-04
        // Given / When
        registrarCliente("cliente.uno@ejemplo.es").andExpect(status().isCreated());
        registrarCliente("cliente.dos@ejemplo.es").andExpect(status().isCreated());
        registrarCliente("cliente.tres@ejemplo.es").andExpect(status().isCreated());

        // Then: el indice parcial no se rompe porque nunca se guarda nombreComercial ni a null ni vacio
        assertThat(usuarios.countDocuments()).isEqualTo(3);
        assertThat(usuarios.find()).allSatisfy(usuario ->
                assertThat(perfilDe(usuario).containsKey("nombreComercial")).isFalse());
    }

    // ------------------------------------------------------------------ CP-INT-05

    static Stream<Arguments> documentosQueIncumplenElEsquema() {
        Document sinHash = documentoCliente("sin.hash@ejemplo.es");
        sinHash.remove("password");

        Document rolFueraDelEnum = documentoCliente("rol.raro@ejemplo.es");
        rolFueraDelEnum.put("rol", List.of("HACKER"));

        // El script limita rol a un unico elemento (maxItems: 1, decision D2)
        Document dosRoles = documentoCliente("dos.roles@ejemplo.es");
        dosRoles.put("rol", List.of("CLIENTE", "PREMIUM"));

        Document telefonoInvalido = documentoCliente("telefono@ejemplo.es");
        telefonoInvalido.get("perfil", Document.class).append("telefono", "12345");

        return Stream.of(
                arguments("sin password", sinHash),
                arguments("rol fuera del enum", rolFueraDelEnum),
                arguments("mas de un rol", dosRoles),
                arguments("telefono que no tiene 9 digitos", telefonoInvalido));
    }

    @ParameterizedTest(name = "el validador de esquema rechaza: {0}")
    @MethodSource("documentosQueIncumplenElEsquema")
    void insertOne_documentoQueIncumpleElEsquema_mongoLoRechazaConErrorDeValidacion( // CP-INT-05
            String descripcion, Document documentoInvalido) {
        // Given: el documento se inserta directamente, saltandose toda la logica de la aplicacion

        // When
        MongoWriteException excepcion =
                assertThrows(MongoWriteException.class, () -> usuarios.insertOne(documentoInvalido));

        // Then
        assertThat(excepcion.getError().getCode()).isEqualTo(CODIGO_ERROR_VALIDACION_ESQUEMA);
    }

    // ------------------------------------------------------------------ CP-INT-06

    @Test
    void registrarCliente_fechaDeNacimiento_sePersisteComoTipoDateYNoComoString() throws Exception { // CP-INT-06
        // Given
        SolicitudRegistroClienteDTO solicitud = ConstructorSolicitudCliente.unaSolicitudValida().construir();

        // When
        registrarCliente(solicitud).andExpect(status().isCreated());

        // Then
        Object fechaPersistida = perfilDe(usuarioPorEmail(ConstructorSolicitudCliente.EMAIL_POR_DEFECTO))
                .get("fechaNacimiento");
        assertThat(fechaPersistida).isInstanceOf(Date.class);
        // A medianoche UTC, no de la zona del servidor: si no, un servidor en otra zona leeria otro dia
        assertThat(((Date) fechaPersistida).toInstant()).isEqualTo(Instant.parse("2000-05-15T00:00:00Z"));
    }

    // ------------------------------------------------------------------ CP-INT-07

    @Test
    void registrarVendedor_categoriaPrincipal_sePersisteComoObjectIdYNoComoString() throws Exception { // CP-INT-07
        // Given
        ObjectId categoriaId = new ObjectId(ConstructorSolicitudVendedor.CATEGORIA_VALIDA);
        categorias.insertOne(new Document("_id", categoriaId).append("nombre", "Electronica"));
        SolicitudRegistroVendedorDTO solicitud = ConstructorSolicitudVendedor.unaSolicitudValida().construir();

        // When
        registrarVendedor(solicitud).andExpect(status().isCreated());

        // Then
        Object categoriaPersistida = perfilDe(usuarioPorEmail(ConstructorSolicitudVendedor.EMAIL_POR_DEFECTO))
                .get("idCategoriaPrincipal");
        assertThat(categoriaPersistida).isInstanceOf(ObjectId.class).isEqualTo(categoriaId);
    }

    // ------------------------------------------------------------------ CP-INT-08

    @Test
    void roles_valoresDelEnumJavaYEnumDelEsquema_estanSincronizados() { // CP-INT-08
        // Given: el enum de roles definido en el esquema de la coleccion usuarios
        Document coleccion = mongo.getDb().listCollections().filter(Filters.eq("name", "usuarios")).first();
        Document esquema = coleccion.get("options", Document.class)
                .get("validator", Document.class)
                .get("$jsonSchema", Document.class);
        List<String> permitidosPorElEsquema = esquema.get("properties", Document.class)
                .get("rol", Document.class)
                .get("items", Document.class)
                .getList("enum", String.class);

        // When
        List<String> valoresDelEnumJava = Arrays.stream(Rol.values()).map(Rol::valorBd).toList();

        // Then
        assertThat(valoresDelEnumJava).containsExactlyInAnyOrderElementsOf(permitidosPorElEsquema);
    }

    @Test
    void registrar_clientePremiumYVendedor_persistenElValorDeBdDelRol() throws Exception { // CP-INT-08
        // Given
        categorias.insertOne(new Document("_id", new ObjectId(ConstructorSolicitudVendedor.CATEGORIA_VALIDA))
                .append("nombre", "Electronica"));

        // When
        registrarCliente(ConstructorSolicitudCliente.unaSolicitudValida()
                .conEmail("premium@ejemplo.es").conTipoCuenta(TipoCuenta.PREMIUM).construir())
                .andExpect(status().isCreated());
        registrarCliente("normal@ejemplo.es").andExpect(status().isCreated());
        registrarVendedor(ConstructorSolicitudVendedor.unaSolicitudValida().construir())
                .andExpect(status().isCreated());

        // Then
        assertThat(usuarioPorEmail("premium@ejemplo.es").getList("rol", String.class)).containsExactly("PREMIUM");
        assertThat(usuarioPorEmail("normal@ejemplo.es").getList("rol", String.class)).containsExactly("CLIENTE");
        assertThat(usuarioPorEmail(ConstructorSolicitudVendedor.EMAIL_POR_DEFECTO).getList("rol", String.class))
                .containsExactly("VENDEDOR");
    }
}
