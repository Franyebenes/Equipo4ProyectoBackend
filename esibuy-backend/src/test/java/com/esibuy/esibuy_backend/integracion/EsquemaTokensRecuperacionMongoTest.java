package com.esibuy.esibuy_backend.integracion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Date;
import java.util.List;
import java.util.stream.Stream;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;

/**
 * Esquema e indices de la coleccion tokens_recuperacion tal como los deja el script de BBDD del proyecto
 * (init-esibuy.js) (HdU recuperacion de contrasena, fase 1). Casos: CP-TOK-40 a CP-TOK-45.
 *
 * El esquema es una segunda barrera por debajo de la aplicacion: aunque un fallo de codigo intentara guardar el token
 * en claro (campo extra) o un hash con otro formato, la base de datos lo rechaza.
 *
 * Terminada: ya no lleva la etiqueta pendiente-recuperacion y entra en la regresion.
 */
@Testcontainers(disabledWithoutDocker = true)
class EsquemaTokensRecuperacionMongoTest {

    private static final int CODIGO_VALIDACION_DE_DOCUMENTO = 121;
    private static final String HASH = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    private static MongoClient cliente;
    private MongoCollection<Document> tokens;

    @BeforeAll
    static void conectar() {
        cliente = MongoClients.create(MongoCompartido.contenedor().getReplicaSetUrl("ESIBuy"));
    }

    @AfterAll
    static void cerrar() {
        if (cliente != null) {
            cliente.close();
        }
    }

    @BeforeEach
    void limpiar() {
        MongoDatabase bd = cliente.getDatabase("ESIBuy");
        tokens = bd.getCollection("tokens_recuperacion");
        tokens.deleteMany(new Document());
    }

    private static Document valido() {
        return new Document("idUsuario", new ObjectId())
                .append("hashToken", HASH)
                .append("estado", "VIGENTE")
                .append("creadoEn", new Date())
                .append("caducaEn", new Date());
    }

    // ------------------------------------------------------------------ CP-TOK-40 / 41

    @Test
    void laColeccionExisteConUnValidadorDeEsquema() { // CP-TOK-40
        Document informacion = cliente.getDatabase("ESIBuy").listCollections()
                .filter(new Document("name", "tokens_recuperacion")).first();

        assertThat(informacion).isNotNull();
        Document opciones = informacion.get("options", Document.class);
        assertThat(opciones.get("validator", Document.class)).containsKey("$jsonSchema");
    }

    @Test
    void aceptaUnDocumentoValidoConYSinFechaDeUso() { // CP-TOK-41
        assertDoesNotThrow(() -> tokens.insertOne(valido()));
        assertDoesNotThrow(() -> tokens.insertOne(
                valido().append("hashToken", "a".repeat(64)).append("estado", "USADO").append("usadoEn", new Date())));
    }

    // ------------------------------------------------------------------ CP-TOK-42

    static Stream<Arguments> documentosInvalidos() {
        return Stream.of(
                Arguments.of("sin hashToken", valido().append("hashToken", null)),
                Arguments.of("hash con mayusculas", valido().append("hashToken", HASH.toUpperCase())),
                Arguments.of("hash de 63 caracteres", valido().append("hashToken", HASH.substring(1))),
                Arguments.of("hash de 65 caracteres", valido().append("hashToken", HASH + "a")),
                Arguments.of("hash que no es hexadecimal", valido().append("hashToken", "g".repeat(64))),
                Arguments.of("estado fuera de la lista", valido().append("estado", "CADUCADO")),
                Arguments.of("estado en minusculas", valido().append("estado", "vigente")),
                Arguments.of("idUsuario como texto", valido().append("idUsuario", new ObjectId().toHexString())),
                Arguments.of("creadoEn como texto", valido().append("creadoEn", "2026-10-16")),
                Arguments.of("caducaEn como texto", valido().append("caducaEn", "2026-10-16")),
                Arguments.of("usadoEn como texto", valido().append("usadoEn", "hoy")),
                Arguments.of("campo extra token (el token en claro)", valido().append("token", "TOKEN-EN-CLARO")),
                Arguments.of("campo extra cualquiera", valido().append("otro", 1)));
    }

    @ParameterizedTest(name = "rechaza: {0}")
    @MethodSource("documentosInvalidos")
    void rechazaLosDocumentosQueNoCumplenElEsquema(String descripcion, Document documento) { // CP-TOK-42
        if (documento.get("hashToken") == null) {
            documento.remove("hashToken");
        }

        MongoWriteException error = assertThrows(MongoWriteException.class, () -> tokens.insertOne(documento));

        assertThat(error.getCode()).isEqualTo(CODIGO_VALIDACION_DE_DOCUMENTO);
    }

    @ParameterizedTest(name = "rechaza sin el campo {0}")
    @MethodSource("camposObligatorios")
    void rechazaLosDocumentosSinUnCampoObligatorio(String campo) { // CP-TOK-42
        Document documento = valido();
        documento.remove(campo);

        MongoWriteException error = assertThrows(MongoWriteException.class, () -> tokens.insertOne(documento));

        assertThat(error.getCode()).isEqualTo(CODIGO_VALIDACION_DE_DOCUMENTO);
    }

    static Stream<String> camposObligatorios() {
        return Stream.of("idUsuario", "hashToken", "estado", "creadoEn", "caducaEn");
    }

    // ------------------------------------------------------------------ CP-TOK-43 / 44 / 45

    @Test
    void elHashEsUnico() { // CP-TOK-43
        tokens.insertOne(valido());

        MongoWriteException error = assertThrows(MongoWriteException.class, () -> tokens.insertOne(valido()));

        assertThat(error.getError().getCategory()).isEqualTo(com.mongodb.ErrorCategory.DUPLICATE_KEY);
    }

    @Test
    void tieneUnIndiceUnicoPorHash() { // CP-TOK-44
        Document indice = indicePorClave(new Document("hashToken", 1));

        assertThat(indice).isNotNull();
        assertThat(indice.getBoolean("unique")).isTrue();
    }

    @Test
    void tieneUnIndiceTtlSobreCaducaEnQueConservaUnDiaLosCaducados() { // CP-TOK-44
        // Se conservan 24 h tras caducar: asi un enlace gastado que alguien reutilice al dia siguiente sigue
        // identificandose como YA_USADO (alerta de seguridad) en lugar de parecer desconocido
        Document indice = indicePorClave(new Document("caducaEn", 1));

        assertThat(indice).isNotNull();
        assertThat(indice.get("expireAfterSeconds", Number.class).longValue()).isEqualTo(86_400L);
    }

    @Test
    void tieneUnIndiceCompuestoPorUsuarioYEstadoParaAnularLosVigentes() { // CP-TOK-45
        assertThat(indicePorClave(new Document("idUsuario", 1).append("estado", 1))).isNotNull();
    }

    private Document indicePorClave(Document clave) {
        List<Document> indices = tokens.listIndexes().into(new java.util.ArrayList<>());
        return indices.stream().filter(i -> clave.equals(i.get("key", Document.class))).findFirst().orElse(null);
    }
}
