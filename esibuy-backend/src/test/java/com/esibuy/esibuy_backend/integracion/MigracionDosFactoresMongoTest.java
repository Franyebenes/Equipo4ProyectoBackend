package com.esibuy.esibuy_backend.integracion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.MountableFile;

import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.CreateCollectionOptions;
import com.mongodb.client.model.ValidationOptions;

/**
 * Script de migracion que anade el subdocumento dosFactores al esquema de usuarios en una base de datos que YA tiene
 * datos, sin borrar nada (HdU 2FA, fase 1). Casos: CP-2FA-120 a CP-2FA-126.
 *
 * El script es scripts_auxiliares/migracion-2fa.js. A diferencia de la migracion de los tokens de recuperacion, aqui no
 * se crea una coleccion sino que se amplia el validador de una que ya existe y tiene usuarios. Por eso el script no
 * puede sustituir el validador por uno suyo (pisaria las reglas que tenga esa base, que pueden no ser las de
 * init-esibuy.js): lee el que hay y le anade la propiedad dosFactores. Se prueba que:
 *  - las reglas anteriores siguen funcionando y las nuevas se aplican,
 *  - los usuarios existentes no se tocan,
 *  - se puede ejecutar varias veces,
 *  - el resultado es el mismo que init-esibuy.js (que usan los entornos nuevos y los tests),
 *  - y no contiene ninguna instruccion que borre o modifique documentos.
 *
 * Terminada: ya no lleva la etiqueta pendiente-2fa y entra en la regresion.
 */
@Testcontainers(disabledWithoutDocker = true)
class MigracionDosFactoresMongoTest {

    private static final Path SCRIPT = Path.of("scripts_auxiliares", "migracion-2fa.js");
    private static final String BD = "ESIBuy";
    private static final String CIFRADO = "1:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String HASH = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    private static MongoDBContainer contenedor;
    private static MongoClient cliente;
    private static Document validadorAnterior;

    @BeforeAll
    static void arrancarUnaBaseDeDatosAnteriorALaHdu() {
        contenedor = new MongoDBContainer("mongo:7.0")
                .withCopyFileToContainer(MountableFile.forHostPath(SCRIPT), "/tmp/migracion.js");
        contenedor.start();
        cliente = MongoClients.create(contenedor.getReplicaSetUrl(BD));
        validadorAnterior = validadorDeUsuariosAnteriorAlDosFactores();
    }

    @AfterAll
    static void parar() {
        if (cliente != null) {
            cliente.close();
        }
        if (contenedor != null) {
            contenedor.stop();
        }
    }

    /**
     * El validador de usuarios "de antes de la HdU": el de init-esibuy.js (la BBDD compartida) sin la propiedad
     * dosFactores. Asi el test no duplica las reglas anteriores y sigue valiendo si cambian.
     */
    private static Document validadorDeUsuariosAnteriorAlDosFactores() {
        try (MongoClient compartida = MongoClients.create(MongoCompartido.contenedor().getReplicaSetUrl(BD))) {
            Document validador = validadorDeUsuarios(compartida);
            Document copia = Document.parse(validador.toJson());
            copia.get("$jsonSchema", Document.class).get("properties", Document.class).remove("dosFactores");
            return copia;
        }
    }

    private static Document validadorDeUsuarios(MongoClient mongo) {
        Document informacion = mongo.getDatabase(BD).listCollections().filter(new Document("name", "usuarios")).first();
        assertThat(informacion).as("la coleccion usuarios debe existir").isNotNull();
        Document opciones = informacion.get("options", Document.class);
        return opciones.get("validator", Document.class);
    }

    private static Document usuarioAntiguo(String email, String estado) {
        return new Document("email", email).append("password", "hash-de-" + email).append("estado", estado)
                .append("rol", List.of("CLIENTE"))
                .append("perfil", new Document("nombre", "Ana").append("apellidos", "Garcia"));
    }

    private static MongoCollection<Document> usuarios() {
        return cliente.getDatabase(BD).getCollection("usuarios");
    }

    /** Cada prueba parte de una coleccion usuarios "antigua" con dos usuarios (limpieza del test, no del script). */
    @BeforeEach
    void volverAlEstadoAnterior() {
        usuarios().drop();
        cliente.getDatabase(BD).createCollection("usuarios", new CreateCollectionOptions()
                .validationOptions(new ValidationOptions().validator(validadorAnterior)));
        usuarios().insertOne(usuarioAntiguo("ana@ejemplo.es", "ACTIVO"));
        usuarios().insertOne(usuarioAntiguo("luis@ejemplo.es", "DESACTIVADO").append("contrasenaCambiadaEn", new Date()));
        usuarios().createIndex(new Document("email", 1), new com.mongodb.client.model.IndexOptions().unique(true));
    }

    private static ExecResult ejecutarMigracion() throws IOException, InterruptedException {
        return contenedor.execInContainer("mongosh", "mongodb://localhost:27017/" + BD + "?directConnection=true",
                "--quiet", "--file", "/tmp/migracion.js");
    }

    private static Document activoValido() {
        return new Document("activo", true).append("secretoCifrado", CIFRADO).append("activadoEn", new Date())
                .append("ultimoPaso", 58_000_000L)
                .append("codigosRecuperacion", List.of(new Document("hash", HASH).append("usado", false)));
    }

    // ------------------------------------------------------------------ CP-2FA-120 / 121

    @Test
    void migrar_sobreUnaBaseConElValidadorAnterior_aplicaLasReglasNuevasYConservaLasAnteriores() throws Exception { // CP-2FA-120
        // Given: antes de migrar, el esquema no conoce dosFactores y acepta cualquier cosa en ese campo
        usuarios().insertOne(usuarioAntiguo("antes@ejemplo.es", "ACTIVO").append("dosFactores", "cualquier cosa"));
        usuarios().deleteOne(new Document("email", "antes@ejemplo.es"));

        // When
        ExecResult resultado = ejecutarMigracion();

        // Then
        assertEquals(0, resultado.getExitCode(), "La migracion fallo: " + resultado.getStderr());
        // las reglas nuevas se aplican
        usuarios().insertOne(usuarioAntiguo("con2fa@ejemplo.es", "ACTIVO").append("dosFactores", activoValido()));
        assertThrows(MongoWriteException.class, () -> usuarios().insertOne(usuarioAntiguo("malo@ejemplo.es", "ACTIVO")
                .append("dosFactores", activoValido().append("secreto", "GEZDGNBV"))));
        assertThrows(MongoWriteException.class, () -> usuarios().insertOne(usuarioAntiguo("malo2@ejemplo.es", "ACTIVO")
                .append("dosFactores", new Document("activo", true))));
        // y las anteriores siguen
        Document sinEmail = usuarioAntiguo("x@ejemplo.es", "ACTIVO");
        sinEmail.remove("email");
        assertThrows(MongoWriteException.class, () -> usuarios().insertOne(sinEmail));
        assertThrows(MongoWriteException.class, () -> usuarios().insertOne(
                usuarioAntiguo("dos@ejemplo.es", "ACTIVO").append("rol", List.of("CLIENTE", "ADMIN"))));
        assertThrows(MongoWriteException.class, () -> usuarios().insertOne(
                usuarioAntiguo("estado@ejemplo.es", "ACTIVO").append("estado", "SUSPENDIDO")));
    }

    @Test
    void migrar_sobreUnaColeccionSinValidador_tambienAplicaLasReglasNuevas() throws Exception { // CP-2FA-121
        // Given: una base muy antigua, cuya coleccion usuarios no tiene ningun validador
        usuarios().drop();
        cliente.getDatabase(BD).createCollection("usuarios");
        usuarios().insertOne(usuarioAntiguo("ana@ejemplo.es", "ACTIVO"));

        // When
        ExecResult resultado = ejecutarMigracion();

        // Then
        assertEquals(0, resultado.getExitCode(), "La migracion fallo: " + resultado.getStderr());
        usuarios().insertOne(usuarioAntiguo("con2fa@ejemplo.es", "ACTIVO").append("dosFactores", activoValido()));
        assertThrows(MongoWriteException.class, () -> usuarios().insertOne(usuarioAntiguo("malo@ejemplo.es", "ACTIVO")
                .append("dosFactores", activoValido().append("secreto", "GEZDGNBV"))));
        assertThat(usuarios().countDocuments()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ CP-2FA-122

    @Test
    void migrar_noTocaLosUsuariosNiLosIndicesExistentes() throws Exception { // CP-2FA-122
        List<Document> antes = usuarios().find().into(new ArrayList<>());
        List<Document> indicesAntes = indicesDeUsuarios();

        ExecResult resultado = ejecutarMigracion();

        assertEquals(0, resultado.getExitCode(), "La migracion fallo: " + resultado.getStderr());
        // los mismos documentos, campo a campo (incluidos _id, contrasenaCambiadaEn y el hash de la contrasena)
        assertThat(usuarios().find().into(new ArrayList<>())).containsExactlyInAnyOrderElementsOf(antes);
        assertThat(indicesDeUsuarios()).isEqualTo(indicesAntes);
        // y sin campos nuevos en ellos
        assertThat(usuarios().find().into(new ArrayList<>())).allSatisfy(u -> assertThat(u).doesNotContainKey("dosFactores"));
    }

    private static List<Document> indicesDeUsuarios() {
        List<Document> indices = usuarios().listIndexes().into(new ArrayList<>());
        indices.forEach(i -> {
            i.remove("v");
            i.remove("ns");
        });
        indices.sort((a, b) -> a.get("name", String.class).compareTo(b.get("name", String.class)));
        return indices;
    }

    // ------------------------------------------------------------------ CP-2FA-123

    @Test
    void migrar_ejecutadaDeNuevo_esIdempotenteYConservaLosDosFactoresYaGuardados() throws Exception { // CP-2FA-123
        // Given: la migracion ya se aplico y hay un usuario con 2FA activo
        assertEquals(0, ejecutarMigracion().getExitCode());
        Document validadorTrasLaPrimera = validadorDeUsuarios(cliente);
        usuarios().insertOne(usuarioAntiguo("con2fa@ejemplo.es", "ACTIVO").append("dosFactores", activoValido()));

        // When: se vuelve a ejecutar (dos veces mas)
        ExecResult segunda = ejecutarMigracion();
        ExecResult tercera = ejecutarMigracion();

        // Then: sin errores, con el mismo validador y el usuario con 2FA intacto
        assertEquals(0, segunda.getExitCode(), "Fallo en la 2a ejecucion: " + segunda.getStderr());
        assertEquals(0, tercera.getExitCode(), "Fallo en la 3a ejecucion: " + tercera.getStderr());
        assertThat(validadorDeUsuarios(cliente)).isEqualTo(validadorTrasLaPrimera);
        Document guardado = usuarios().find(new Document("email", "con2fa@ejemplo.es")).first();
        assertThat(guardado.get("dosFactores", Document.class).getBoolean("activo")).isTrue();
        assertThat(usuarios().countDocuments()).isEqualTo(3);
    }

    @Test
    void migrar_losUsuariosAntiguosSiguenSiendoActualizablesDespues() throws Exception { // CP-2FA-123
        assertEquals(0, ejecutarMigracion().getExitCode());

        // Un usuario de antes de la HdU, sin el campo, se puede seguir guardando (bloquear, cambiar la contrasena...)
        usuarios().updateOne(new Document("email", "ana@ejemplo.es"),
                new Document("$set", new Document("estado", "BLOQUEADO").append("contrasenaCambiadaEn", new Date())));

        assertThat(usuarios().find(new Document("email", "ana@ejemplo.es")).first().getString("estado"))
                .isEqualTo("BLOQUEADO");
    }

    // ------------------------------------------------------------------ CP-2FA-124

    @Test
    void migrar_dejaElValidadorIgualQueInitEsibuy() throws Exception { // CP-2FA-124
        // Given: la migracion aplicada sobre la base "antigua" y el script de inicio aplicado sobre la compartida
        assertEquals(0, ejecutarMigracion().getExitCode());
        try (MongoClient compartida = MongoClients.create(MongoCompartido.contenedor().getReplicaSetUrl(BD))) {
            // When / Then: mismo validador, para que migracion e inicio no se desincronicen
            assertThat(validadorDeUsuarios(cliente)).isEqualTo(validadorDeUsuarios(compartida));
        }
    }

    // ------------------------------------------------------------------ CP-2FA-125 / 126

    @Test
    void elScriptNoBorraNiModificaDocumentos() throws IOException { // CP-2FA-125
        // Solo se miran las lineas de codigo, no los comentarios
        String codigo = codigoDelScript();

        assertThat(codigo).doesNotContainPattern("\\.(drop|dropdatabase|deletemany|deleteone|remove|dropindex|dropindexes)\\s*\\(");
        assertThat(codigo).doesNotContainPattern(
                "\\.(updatemany|updateone|replaceone|insertone|insertmany|bulkwrite|findandmodify|findoneandupdate|"
                        + "findoneandreplace|findoneanddelete|save|createindex|createindexes)\\s*\\(");
        assertThat(codigo).doesNotContain("$out", "$merge", "$set", "$unset");
        // La unica escritura es ampliar el validador
        assertThat(codigo).contains("collmod");
    }

    @Test
    void elScriptSoloTrabajaConLaColeccionUsuarios() throws IOException { // CP-2FA-126
        String codigo = codigoDelScript();

        assertThat(codigo).contains("usuarios");
        assertThat(codigo).doesNotContain("productos", "pedidos", "categorias", "valoraciones", "incidencias",
                "tokens_recuperacion");
    }

    private static String codigoDelScript() throws IOException {
        return Files.readAllLines(SCRIPT).stream()
                .map(String::strip)
                .filter(linea -> !linea.startsWith("//") && !linea.startsWith("*") && !linea.startsWith("/*"))
                .reduce("", (a, b) -> a + "\n" + b)
                .toLowerCase(Locale.ROOT);
    }
}
