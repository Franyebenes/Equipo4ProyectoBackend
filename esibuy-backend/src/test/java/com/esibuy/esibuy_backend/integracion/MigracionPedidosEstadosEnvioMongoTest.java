package com.esibuy.esibuy_backend.integracion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;

/**
 * Script de migracion del seguimiento del envio (Procesar estado del pedido y Estado del envio):
 * scripts_auxiliares/migracion-pedidos-estados-envio.js. Se ejecuta con mongosh contra una base de datos aparte que
 * esta como antes de estas HUs (con el validador de la migracion anterior y un pedido) y se comprueba que:
 *  - despues de aplicarla se aceptan los estados y campos nuevos, y antes no,
 *  - no toca los pedidos que ya hay,
 *  - se puede ejecutar varias veces,
 *  - deja la coleccion exactamente igual que init-esibuy.js (que es lo que usan los entornos nuevos y los tests),
 *  - y no contiene ninguna instruccion destructiva.
 */
@Testcontainers(disabledWithoutDocker = true)
class MigracionPedidosEstadosEnvioMongoTest {

    private static final Path SCRIPT = Path.of("scripts_auxiliares", "migracion-pedidos-estados-envio.js");
    private static final Path SCRIPT_ANTERIOR = Path.of("scripts_auxiliares", "migracion-pedidos-estado-finalizado.js");
    private static final String BD = "ESIBuy";
    private static final String PEDIDOS = "pedidos";
    private static final ObjectId ID_PEDIDO = new ObjectId("64c000000000000000000101");

    private static MongoDBContainer contenedor;
    private static MongoClient cliente;

    @BeforeAll
    static void arrancarUnaBaseDeDatosAnteriorALasHus() {
        contenedor = new MongoDBContainer("mongo:7.0")
                .withCopyFileToContainer(MountableFile.forHostPath(SCRIPT), "/tmp/migracion.js")
                .withCopyFileToContainer(MountableFile.forHostPath(SCRIPT_ANTERIOR), "/tmp/anterior.js");
        contenedor.start();
        cliente = MongoClients.create(contenedor.getReplicaSetUrl(BD));
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
     * Cada prueba parte de la base "antigua": la coleccion de pedidos con el validador de la migracion anterior y un
     * pedido PAGADO (esto es limpieza del test, no del script).
     */
    @BeforeEach
    void volverAlEstadoAnterior() throws Exception {
        pedidos().drop();
        ExecResult anterior = ejecutar("/tmp/anterior.js");
        assertEquals(0, anterior.getExitCode(), "Fallo la migracion anterior: " + anterior.getStderr());
        pedidos().insertOne(pedidoAntiguo());
    }

    @Test
    void migrar_aceptaLosEstadosYCamposNuevosSinTocarLosPedidos() throws Exception {
        // Given: con el validador anterior, EN_PREPARACION no se puede guardar
        assertThatThrownBy(() -> pedidos().updateOne(Filters.eq("_id", ID_PEDIDO),
                Updates.set("estado", "EN_PREPARACION")))
                .isInstanceOf(MongoWriteException.class);

        // When
        ExecResult resultado = ejecutar("/tmp/migracion.js");

        // Then: el pedido sigue exactamente igual...
        assertEquals(0, resultado.getExitCode(), "La migracion fallo: " + resultado.getStderr());
        assertThat(pedidos().find(Filters.eq("_id", ID_PEDIDO)).first()).isEqualTo(pedidoAntiguo());
        // ...y ya se puede avanzar su envio guardando la fecha y el seguimiento
        Date ahora = new Date();
        pedidos().updateOne(Filters.eq("_id", ID_PEDIDO), Updates.combine(
                Updates.set("estado", "EN_PREPARACION"),
                Updates.set("fechaActualizacion", ahora),
                Updates.set("fechaEntregaEstimada", ahora),
                Updates.push("seguimiento", new Document("estado", "EN_PREPARACION").append("fecha", ahora))));
        assertThat(pedidos().find(Filters.eq("_id", ID_PEDIDO)).first().getString("estado"))
                .isEqualTo("EN_PREPARACION");
    }

    @Test
    void migrar_sigueRechazandoUnEstadoQueNoExiste() throws Exception {
        // Given
        assertEquals(0, ejecutar("/tmp/migracion.js").getExitCode());
        // When / Then
        assertThatThrownBy(() -> pedidos().updateOne(Filters.eq("_id", ID_PEDIDO), Updates.set("estado", "VOLANDO")))
                .isInstanceOf(MongoWriteException.class);
    }

    @Test
    void migrar_ejecutadaVariasVeces_esIdempotenteYConservaLosPedidos() throws Exception {
        // Given
        assertEquals(0, ejecutar("/tmp/migracion.js").getExitCode());

        // When: se vuelve a ejecutar (dos veces mas)
        ExecResult segunda = ejecutar("/tmp/migracion.js");
        ExecResult tercera = ejecutar("/tmp/migracion.js");

        // Then: sin errores, con el mismo pedido y los mismos indices
        assertEquals(0, segunda.getExitCode(), "Fallo en la 2a ejecucion: " + segunda.getStderr());
        assertEquals(0, tercera.getExitCode(), "Fallo en la 3a ejecucion: " + tercera.getStderr());
        assertThat(pedidos().countDocuments()).isEqualTo(1);
        assertThat(pedidos().listIndexes().into(new ArrayList<>())).hasSize(4); // _id + los 3 propios
    }

    @Test
    void migrar_dejaLaColeccionIgualQueInitEsibuy() throws Exception {
        // Given: la migracion aplicada sobre la base "antigua" y el script de inicio aplicado sobre la compartida
        assertEquals(0, ejecutar("/tmp/migracion.js").getExitCode());
        try (MongoClient compartida = MongoClients.create(MongoCompartido.contenedor().getReplicaSetUrl(BD))) {
            // When / Then: mismo validador y mismos indices, para que no se desincronicen
            assertThat(validadorDe(cliente)).isEqualTo(validadorDe(compartida));
            assertThat(indicesDe(cliente)).isEqualTo(indicesDe(compartida));
        }
    }

    @Test
    void elScriptNoContieneInstruccionesDestructivas() throws IOException {
        // Solo se miran las lineas de codigo, no los comentarios
        String codigo = Files.readAllLines(SCRIPT).stream()
                .map(String::strip)
                .filter(linea -> !linea.startsWith("//") && !linea.startsWith("*") && !linea.startsWith("/*"))
                .reduce("", (a, b) -> a + "\n" + b)
                .toLowerCase(Locale.ROOT);

        assertThat(codigo).doesNotContainPattern(
                "\\.(drop|dropdatabase|deletemany|deleteone|remove|dropindex|dropindexes|updatemany|updateone)\\s*\\(");
        assertThat(codigo).doesNotContain("$out", "$merge");
        // y solo trabaja con la coleccion de pedidos
        assertThat(codigo).doesNotContain("db.usuarios", "db.productos", "db.categorias", "db.valoraciones");
    }

    private static MongoCollection<Document> pedidos() {
        return cliente.getDatabase(BD).getCollection(PEDIDOS);
    }

    private static ExecResult ejecutar(String script) throws IOException, InterruptedException {
        return contenedor.execInContainer("mongosh", "mongodb://localhost:27017/" + BD + "?directConnection=true",
                "--quiet", "--file", script);
    }

    // Un pedido como los que ya hay en la base: sin los campos de seguimiento
    private static Document pedidoAntiguo() {
        return new Document("_id", ID_PEDIDO)
                .append("idCliente", new ObjectId("64c000000000000000000011"))
                .append("idVendedor", new ObjectId("64c000000000000000000001"))
                .append("estado", "PAGADO")
                .append("precioTotal", 18.0)
                .append("fecha", new Date(1_793_000_000_000L))
                .append("direccionEnvio", new Document("calle", "Calle Mayor 1").append("ciudad", "Ciudad Real")
                        .append("codigoPostal", "13001"))
                .append("lineas", List.of(new Document("idProducto", new ObjectId("64c000000000000000000021"))
                        .append("nombre", "Teclado").append("cantidad", 1).append("precioUnitario", 18.0)));
    }

    private static Document validadorDe(MongoClient mongo) {
        Document informacion = mongo.getDatabase(BD).listCollections().filter(new Document("name", PEDIDOS)).first();
        assertThat(informacion).as("la coleccion pedidos debe existir").isNotNull();
        return informacion.get("options", Document.class).get("validator", Document.class);
    }

    private static List<Document> indicesDe(MongoClient mongo) {
        List<Document> indices = mongo.getDatabase(BD).getCollection(PEDIDOS).listIndexes().into(new ArrayList<>());
        // Se ignora la version, que no forma parte de la definicion
        indices.forEach(i -> {
            i.remove("v");
            i.remove("ns");
        });
        indices.sort((a, b) -> a.get("name", String.class).compareTo(b.get("name", String.class)));
        return indices;
    }
}
