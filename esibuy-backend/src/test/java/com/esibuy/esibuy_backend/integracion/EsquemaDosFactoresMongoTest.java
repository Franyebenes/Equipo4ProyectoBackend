package com.esibuy.esibuy_backend.integracion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;

/**
 * Esquema del subdocumento dosFactores en la coleccion usuarios, tal como lo deja el script de BBDD del proyecto
 * (init-esibuy.js) (HdU 2FA, fase 1). Casos: CP-2FA-100 a CP-2FA-119.
 *
 * El esquema es una segunda barrera por debajo de la aplicacion: aunque un fallo de codigo intentara guardar el secreto
 * TOTP en claro (un campo "secreto", o un Base32 en secretoCifrado), un codigo de recuperacion sin hashear o una cuenta
 * "activa" sin secreto, la base de datos lo rechaza. Las cuentas que no tienen el campo (todas las actuales) siguen
 * siendo validas, y las reglas anteriores de usuarios no cambian.
 *
 * Terminada: ya no lleva la etiqueta pendiente-2fa y entra en la regresion.
 */
@Testcontainers(disabledWithoutDocker = true)
class EsquemaDosFactoresMongoTest {

    private static final int CODIGO_VALIDACION_DE_DOCUMENTO = 121;
    private static final String CIFRADO = "1:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String HASH = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    private static MongoClient cliente;
    private MongoCollection<Document> usuarios;

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

    private MongoCollection<Document> usuarios() {
        if (usuarios == null) {
            usuarios = cliente.getDatabase("ESIBuy").getCollection("usuarios");
        }
        return usuarios;
    }

    @AfterEach
    void limpiar() {
        usuarios().deleteMany(Filters.regex("email", "^esquema-2fa-"));
    }

    private static Document usuarioSin2fa() {
        return new Document("email", "esquema-2fa-" + UUID.randomUUID() + "@ejemplo.es")
                .append("password", "hash")
                .append("estado", "ACTIVO")
                .append("rol", List.of("CLIENTE"))
                .append("perfil", new Document("nombre", "Ana").append("apellidos", "Garcia"));
    }

    private static Document pendienteValido() {
        return new Document("secretoCifrado", CIFRADO).append("creadoEn", new Date());
    }

    private static Document codigo(String hash, boolean usado) {
        Document codigo = new Document("hash", hash).append("usado", usado);
        return usado ? codigo.append("usadoEn", new Date()) : codigo;
    }

    private static Document activoValido() {
        return new Document("activo", true)
                .append("secretoCifrado", CIFRADO)
                .append("activadoEn", new Date())
                .append("ultimoPaso", 58_000_000L)
                .append("codigosRecuperacion", List.of(codigo(HASH, false), codigo("a".repeat(64), true)));
    }

    private Document usuarioCon(Document dosFactores) {
        return usuarioSin2fa().append("dosFactores", dosFactores);
    }

    // ---------------------------------------------------------------- lo que acepta

    @Test
    void aceptaUnUsuarioSinDosFactores() { // CP-2FA-100
        // Es el caso de todas las cuentas que ya existen
        assertDoesNotThrow(() -> usuarios().insertOne(usuarioSin2fa()));
    }

    @Test
    void aceptaUnaActivacionPendiente() { // CP-2FA-101
        assertDoesNotThrow(() -> usuarios().insertOne(
                usuarioCon(new Document("activo", false).append("pendiente", pendienteValido()))));
    }

    @Test
    void aceptaUnSegundoFactorActivoCompleto() { // CP-2FA-102
        assertDoesNotThrow(() -> usuarios().insertOne(usuarioCon(activoValido())));
    }

    @Test
    void aceptaElPasoComoEnteroCortoOLargo() { // CP-2FA-103
        assertDoesNotThrow(() -> usuarios().insertOne(usuarioCon(activoValido().append("ultimoPaso", 5))));
        assertDoesNotThrow(() -> usuarios().insertOne(usuarioCon(activoValido().append("ultimoPaso", 3_000_000_000L))));
        assertDoesNotThrow(() -> usuarios().insertOne(usuarioCon(activoValido().append("ultimoPaso", 0L))));
    }

    @Test
    void aceptaUnActivoSinPasoAceptadoTodavia() { // CP-2FA-103
        Document sinPaso = activoValido();
        sinPaso.remove("ultimoPaso");

        assertDoesNotThrow(() -> usuarios().insertOne(usuarioCon(sinPaso)));
    }

    // ---------------------------------------------------------------- lo que rechaza

    private static Stream<Arguments> documentosInvalidos() {
        return Stream.of(
                Arguments.of("campo desconocido (un secreto en claro)", cambio(d -> d.append("secreto", "GEZDGNBVGY3TQOJQ"))),
                Arguments.of("sin el campo activo", cambio(d -> d.remove("activo"))),
                Arguments.of("activo como texto", cambio(d -> d.append("activo", "true"))),
                Arguments.of("activo sin secretoCifrado", cambio(d -> d.remove("secretoCifrado"))),
                Arguments.of("activo sin fecha de activacion", cambio(d -> d.remove("activadoEn"))),
                Arguments.of("activo sin codigos de recuperacion", cambio(d -> d.remove("codigosRecuperacion"))),
                Arguments.of("secretoCifrado en Base32 sin cifrar",
                        cambio(d -> d.append("secretoCifrado", "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"))),
                Arguments.of("secretoCifrado sin version", cambio(d -> d.append("secretoCifrado", "AAAAAAAAAAAA"))),
                Arguments.of("secretoCifrado vacio", cambio(d -> d.append("secretoCifrado", ""))),
                Arguments.of("secretoCifrado como numero", cambio(d -> d.append("secretoCifrado", 12345))),
                Arguments.of("paso negativo", cambio(d -> d.append("ultimoPaso", -1L))),
                Arguments.of("paso como texto", cambio(d -> d.append("ultimoPaso", "58000000"))),
                Arguments.of("paso decimal", cambio(d -> d.append("ultimoPaso", 1.5))),
                Arguments.of("fecha de activacion como texto", cambio(d -> d.append("activadoEn", "2026-10-20"))),
                Arguments.of("codigos de recuperacion que no son una lista",
                        cambio(d -> d.append("codigosRecuperacion", "ABCD-EFGH-2345-6723"))),
                Arguments.of("hash corto", cambio(d -> d.append("codigosRecuperacion", List.of(codigo("abc", false))))),
                Arguments.of("hash en mayusculas",
                        cambio(d -> d.append("codigosRecuperacion", List.of(codigo(HASH.toUpperCase(), false))))),
                Arguments.of("codigo en claro",
                        cambio(d -> d.append("codigosRecuperacion", List.of(codigo("ABCD-EFGH-2345-6723", false))))),
                Arguments.of("codigo sin el campo usado",
                        cambio(d -> d.append("codigosRecuperacion", List.of(new Document("hash", HASH))))),
                Arguments.of("codigo con un campo de mas",
                        cambio(d -> d.append("codigosRecuperacion",
                                List.of(codigo(HASH, false).append("codigo", "ABCD-EFGH-2345-6723"))))),
                Arguments.of("codigo con la fecha de uso como texto",
                        cambio(d -> d.append("codigosRecuperacion",
                                List.of(codigo(HASH, true).append("usadoEn", "ayer"))))),
                Arguments.of("mas de 20 codigos", cambio(d -> d.append("codigosRecuperacion", veinteYUnCodigos()))));
    }

    private static List<Document> veinteYUnCodigos() {
        return java.util.stream.IntStream.rangeClosed(1, 21).mapToObj(i -> codigo(String.format("%064x", i), false))
                .toList();
    }

    /** Parte de un dosFactores activo valido y le aplica una alteracion. */
    private static Document cambio(Consumer<Document> alteracion) {
        Document dosFactores = activoValido();
        alteracion.accept(dosFactores);
        return dosFactores;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("documentosInvalidos")
    void rechazaUnDosFactoresInvalido(String descripcion, Document dosFactores) { // CP-2FA-104 a CP-2FA-112
        MongoWriteException error = assertThrows(MongoWriteException.class,
                () -> usuarios().insertOne(usuarioCon(dosFactores)), descripcion);

        assertThat(error.getError().getCode()).isEqualTo(CODIGO_VALIDACION_DE_DOCUMENTO);
    }

    @Test
    void rechazaUnaActivacionPendienteSinFechaOConUnCampoDeMas() { // CP-2FA-113
        assertThrows(MongoWriteException.class, () -> usuarios().insertOne(usuarioCon(
                new Document("activo", false).append("pendiente", new Document("secretoCifrado", CIFRADO)))));
        assertThrows(MongoWriteException.class, () -> usuarios().insertOne(usuarioCon(
                new Document("activo", false).append("pendiente", pendienteValido().append("secreto", "GEZDGNBV")))));
        assertThrows(MongoWriteException.class, () -> usuarios().insertOne(usuarioCon(
                new Document("activo", false).append("pendiente",
                        pendienteValido().append("secretoCifrado", "GEZDGNBVGY3TQOJQ")))));
    }

    @Test
    void rechazaUnDosFactoresQueNoEsUnObjeto() { // CP-2FA-114
        assertThrows(MongoWriteException.class, () -> usuarios().insertOne(usuarioSin2fa().append("dosFactores", "si")));
        assertThrows(MongoWriteException.class, () -> usuarios().insertOne(usuarioSin2fa().append("dosFactores", true)));
        assertThrows(MongoWriteException.class, () -> usuarios().insertOne(usuarioSin2fa().append("dosFactores", List.of())));
    }

    // ---------------------------------------------------------------- tambien al actualizar

    @Test
    void tambienValidaLasActualizaciones() { // CP-2FA-115
        Document usuario = usuarioCon(activoValido());
        usuarios().insertOne(usuario);

        // Una actualizacion valida pasa
        assertDoesNotThrow(() -> usuarios().updateOne(Filters.eq("_id", usuario.get("_id")),
                Updates.set("dosFactores.ultimoPaso", 58_000_001L)));
        // Una invalida no: no se puede dejar un paso negativo, ni anadir un campo, ni marcar un codigo con un hash roto
        assertThrows(MongoWriteException.class, () -> usuarios().updateOne(Filters.eq("_id", usuario.get("_id")),
                Updates.set("dosFactores.ultimoPaso", -5L)));
        assertThrows(MongoWriteException.class, () -> usuarios().updateOne(Filters.eq("_id", usuario.get("_id")),
                Updates.set("dosFactores.secreto", "GEZDGNBV")));
        assertThrows(MongoWriteException.class, () -> usuarios().updateOne(Filters.eq("_id", usuario.get("_id")),
                Updates.set("dosFactores.codigosRecuperacion.0.hash", "no-es-un-hash")));
    }

    @Test
    void unUsuarioAntiguoSigueSiendoActualizableSinTocarElDosFactores() { // CP-2FA-116
        // Las cuentas que ya existian no tienen el campo y deben poder seguir guardandose (bloquear, cambiar contrasena...)
        Document usuario = usuarioSin2fa();
        usuarios().insertOne(usuario);

        assertDoesNotThrow(() -> usuarios().updateOne(Filters.eq("_id", usuario.get("_id")),
                Updates.combine(Updates.set("estado", "BLOQUEADO"), Updates.set("contrasenaCambiadaEn", new Date()))));
    }

    @Test
    void quitarElDosFactoresDeUnUsuarioEsValido() { // CP-2FA-117
        Document usuario = usuarioCon(activoValido());
        usuarios().insertOne(usuario);

        assertDoesNotThrow(() -> usuarios().updateOne(Filters.eq("_id", usuario.get("_id")),
                Updates.unset("dosFactores")));
        assertThat(usuarios().find(Filters.eq("_id", usuario.get("_id"))).first()).doesNotContainKey("dosFactores");
    }

    // ---------------------------------------------------------------- lo anterior no cambia

    @Test
    void sigueExigiendoLasReglasAnterioresDeUsuarios() { // CP-2FA-118
        Document sinEmail = usuarioSin2fa();
        sinEmail.remove("email");
        Document dosRoles = usuarioSin2fa().append("rol", List.of("CLIENTE", "ADMIN"));
        Document estadoInventado = usuarioSin2fa().append("estado", "SUSPENDIDO");

        assertThrows(MongoWriteException.class, () -> usuarios().insertOne(sinEmail));
        assertThrows(MongoWriteException.class, () -> usuarios().insertOne(dosRoles));
        assertThrows(MongoWriteException.class, () -> usuarios().insertOne(estadoInventado));
    }

    @Test
    void sigueMantenidoElIndiceUnicoDeEmail() { // CP-2FA-119
        Document uno = usuarioSin2fa();
        usuarios().insertOne(uno);
        Document duplicado = usuarioSin2fa().append("email", uno.getString("email"));

        assertThrows(MongoWriteException.class, () -> usuarios().insertOne(duplicado));
    }
}
