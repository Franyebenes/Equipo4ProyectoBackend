package com.esibuy.esibuy_backend.integracion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.MountableFile;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;

/**
 * Script de migracion que anade la coleccion tokens_recuperacion a una base de datos que YA tiene datos, sin borrar
 * nada (HdU recuperacion de contrasena, fase 1). Casos: CP-TOK-50 a CP-TOK-54.
 *
 * El script es scripts_auxiliares/migracion-recuperacion-contrasena.js. Se ejecuta con mongosh contra una base de
 * datos aparte (un contenedor propio, anterior a la HdU, con usuarios) y se comprueba que:
 *  - crea la coleccion y los indices,
 *  - no toca los datos existentes,
 *  - se puede ejecutar varias veces,
 *  - deja la coleccion exactamente igual que init-esibuy.js (que es lo que usan los entornos nuevos y los tests),
 *  - y no contiene ninguna instruccion destructiva.
 *
 * Terminada: ya no lleva la etiqueta pendiente-recuperacion y entra en la regresion.
 */
@Testcontainers(disabledWithoutDocker = true)
class MigracionTokensRecuperacionMongoTest {

    private static final Path SCRIPT = Path.of("scripts_auxiliares", "migracion-recuperacion-contrasena.js");
    private static final String BD = "ESIBuy";

    private static MongoDBContainer contenedor;
    private static MongoClient cliente;

    @BeforeAll
    static void arrancarUnaBaseDeDatosAnteriorALaHdu() throws Exception {
        contenedor = new MongoDBContainer("mongo:7.0")
                .withCopyFileToContainer(MountableFile.forHostPath(SCRIPT), "/tmp/migracion.js");
        contenedor.start();
        cliente = MongoClients.create(contenedor.getReplicaSetUrl(BD));

        // Una base de datos "antigua": con usuarios y sin la coleccion de tokens
        MongoCollection<Document> usuarios = cliente.getDatabase(BD).getCollection("usuarios");
        usuarios.insertOne(new Document("email", "ana@ejemplo.es").append("password", "hash-1").append("estado", "ACTIVO"));
        usuarios.insertOne(new Document("email", "luis@ejemplo.es").append("password", "hash-2").append("estado", "DESACTIVADO"));
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

    /** Cada prueba parte de la base "antigua": sin la coleccion de tokens (esto es limpieza del test, no del script). */
    @BeforeEach
    void volverAlEstadoAnterior() {
        cliente.getDatabase(BD).getCollection("tokens_recuperacion").drop();
    }

    private static ExecResult ejecutarMigracion() throws IOException, InterruptedException {
        return contenedor.execInContainer("mongosh", "mongodb://localhost:27017/" + BD + "?directConnection=true", "--quiet", "--file",
                "/tmp/migracion.js");
    }

    private static List<String> nombresDeColecciones(MongoClient mongo) {
        return mongo.getDatabase(BD).listCollectionNames().into(new ArrayList<>());
    }

    // ------------------------------------------------------------------ CP-TOK-50 / 51

    @Test
    void migrar_creaLaColeccionYLosIndicesSinTocarLosUsuarios() throws Exception { // CP-TOK-50
        // Given: la coleccion todavia no existe
        assertThat(nombresDeColecciones(cliente)).doesNotContain("tokens_recuperacion");

        // When
        ExecResult resultado = ejecutarMigracion();

        // Then
        assertEquals(0, resultado.getExitCode(), "La migracion fallo: " + resultado.getStderr());
        assertThat(nombresDeColecciones(cliente)).contains("tokens_recuperacion", "usuarios");
        MongoCollection<Document> tokens = cliente.getDatabase(BD).getCollection("tokens_recuperacion");
        assertThat(tokens.listIndexes().into(new ArrayList<>())).extracting(i -> i.get("key", Document.class))
                .contains(new Document("hashToken", 1), new Document("caducaEn", 1),
                        new Document("idUsuario", 1).append("estado", 1));

        // y los usuarios existentes siguen exactamente como estaban
        List<Document> usuarios = cliente.getDatabase(BD).getCollection("usuarios").find().into(new ArrayList<>());
        assertThat(usuarios).extracting(u -> u.getString("email")).containsExactlyInAnyOrder("ana@ejemplo.es", "luis@ejemplo.es");
        assertThat(usuarios).extracting(u -> u.getString("password")).containsExactlyInAnyOrder("hash-1", "hash-2");
    }

    @Test
    void migrar_ejecutadaDeNuevo_esIdempotenteYConservaLosTokensYaGuardados() throws Exception { // CP-TOK-51
        // Given: la migracion ya se aplico y hay un token guardado
        assertEquals(0, ejecutarMigracion().getExitCode());
        MongoCollection<Document> tokens = cliente.getDatabase(BD).getCollection("tokens_recuperacion");
        tokens.insertOne(new Document("idUsuario", new ObjectId()).append("hashToken", "a".repeat(64))
                .append("estado", "VIGENTE").append("creadoEn", new Date()).append("caducaEn", new Date()));

        // When: se vuelve a ejecutar (dos veces mas)
        ExecResult segunda = ejecutarMigracion();
        ExecResult tercera = ejecutarMigracion();

        // Then: sin errores, con el mismo numero de indices y el token intacto
        assertEquals(0, segunda.getExitCode(), "Fallo en la 2a ejecucion: " + segunda.getStderr());
        assertEquals(0, tercera.getExitCode(), "Fallo en la 3a ejecucion: " + tercera.getStderr());
        assertThat(tokens.countDocuments()).isEqualTo(1);
        assertThat(tokens.listIndexes().into(new ArrayList<>())).hasSize(4); // _id + los 3 propios
    }

    // ------------------------------------------------------------------ CP-TOK-52 / 53

    @Test
    void migrar_dejaLaColeccionIgualQueInitEsibuy() throws Exception { // CP-TOK-52
        // Given: la migracion aplicada sobre la base "antigua" y el script de inicio aplicado sobre la compartida
        assertEquals(0, ejecutarMigracion().getExitCode());
        try (MongoClient compartida = MongoClients.create(MongoCompartido.contenedor().getReplicaSetUrl(BD))) {
            // When / Then: mismo validador y mismos indices (con sus opciones), para que no se desincronicen
            assertThat(validadorDe(cliente)).isEqualTo(validadorDe(compartida));
            assertThat(indicesDe(cliente)).isEqualTo(indicesDe(compartida));
        }
    }

    private static Document validadorDe(MongoClient mongo) {
        Document informacion = mongo.getDatabase(BD).listCollections()
                .filter(new Document("name", "tokens_recuperacion")).first();
        assertThat(informacion).as("la coleccion tokens_recuperacion debe existir").isNotNull();
        return informacion.get("options", Document.class).get("validator", Document.class);
    }

    private static List<Document> indicesDe(MongoClient mongo) {
        MongoDatabase bd = mongo.getDatabase(BD);
        List<Document> indices = bd.getCollection("tokens_recuperacion").listIndexes().into(new ArrayList<>());
        // Se ignora el nombre y la version, que no forman parte de la definicion
        indices.forEach(i -> {
            i.remove("v");
            i.remove("ns");
        });
        indices.sort((a, b) -> a.get("name", String.class).compareTo(b.get("name", String.class)));
        return indices;
    }

    @Test
    void elScriptNoContieneInstruccionesDestructivas() throws IOException { // CP-TOK-53
        // Solo se miran las lineas de codigo, no los comentarios
        String codigo = Files.readAllLines(SCRIPT).stream()
                .map(String::strip)
                .filter(linea -> !linea.startsWith("//") && !linea.startsWith("*") && !linea.startsWith("/*"))
                .reduce("", (a, b) -> a + "\n" + b)
                .toLowerCase(Locale.ROOT);

        assertThat(codigo).doesNotContainPattern("\\.(drop|dropdatabase|deletemany|deleteone|remove|dropindex|dropindexes)\\s*\\(");
        assertThat(codigo).doesNotContain("$out", "$merge");
        // y solo trabaja con la coleccion nueva
        assertThat(codigo).doesNotContain("db.usuarios", "db.productos", "db.pedidos", "db.categorias");
    }
}
