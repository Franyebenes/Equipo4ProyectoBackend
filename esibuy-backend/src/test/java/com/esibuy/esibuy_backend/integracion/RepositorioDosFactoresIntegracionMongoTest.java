package com.esibuy.esibuy_backend.integracion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.esibuy.esibuy_backend.modelo.DosFactores;
import com.esibuy.esibuy_backend.modelo.Usuario;
import com.esibuy.esibuy_backend.repositorio.RepositorioDosFactores;
import com.esibuy.esibuy_backend.repositorio.RepositorioUsuario;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;

/**
 * Operaciones atomicas sobre el segundo factor contra MongoDB real (HdU 2FA, fase 1). Casos: CP-2FA-127 a CP-2FA-166.
 *
 * Hace falta MongoDB real (y no mocks) porque lo que se prueba es justo lo que los mocks no reproducen: que cada cambio
 * es una unica operacion de la base de datos que no se puede colar dos veces con peticiones simultaneas (un codigo TOTP
 * o de recuperacion que vale una sola vez), que los documentos que escribe la aplicacion los acepta el esquema de
 * init-esibuy.js, y que guardar un usuario entero con Spring Data no estropea su dosFactores.
 *
 * Reglas que fijan:
 *  - Solo se puede iniciar una activacion en una cuenta que no tiene el 2FA activo; repetirla sustituye al secreto
 *    pendiente anterior.
 *  - Un secreto pendiente solo se activa si no ha caducado (a los 10 minutos exactos ya no) y si es exactamente el que
 *    se ha verificado: si se sustituyo entre medias, no se activa.
 *  - El ultimo paso aceptado solo puede avanzar: con peticiones simultaneas del mismo codigo, solo una gana.
 *  - Un codigo de recuperacion se consume una sola vez, incluso con peticiones simultaneas.
 *  - Estas operaciones solo tocan dosFactores: el resto del usuario queda exactamente igual.
 *  - Los identificadores y datos mal formados se rechazan sin llegar a la base de datos.
 *
 * Terminada: ya no lleva la etiqueta pendiente-2fa y entra en la regresion.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class RepositorioDosFactoresIntegracionMongoTest extends IntegracionLoginBase {

    private static final String PREFIJO_EMAIL = "repo-2fa-";
    private static final String CIFRADO_A = "1:" + "A".repeat(64);
    private static final String CIFRADO_B = "1:" + "B".repeat(64);
    private static final Instant AHORA = Instant.parse("2026-10-20T10:00:00Z");
    private static final Duration CADUCIDAD = Duration.ofMinutes(10);

    @Autowired
    private RepositorioDosFactores repositorio;
    @Autowired
    private RepositorioUsuario repositorioUsuario;
    @Autowired
    private MongoTemplate mongo;

    private MongoCollection<Document> usuarios;

    @BeforeEach
    void prepararColeccion() {
        usuarios = mongo.getCollection("usuarios");
    }

    @AfterEach
    void limpiar() {
        usuarios.deleteMany(Filters.regex("email", "^" + PREFIJO_EMAIL));
    }

    // ------------------------------------------------------------------ ayudas

    private static List<String> hashes(int cantidad) {
        return IntStream.rangeClosed(1, cantidad).mapToObj(i -> String.format("%064x", i)).toList();
    }

    private static Document codigo(String hash, boolean usado) {
        Document codigo = new Document("hash", hash).append("usado", usado);
        return usado ? codigo.append("usadoEn", Date.from(AHORA)) : codigo;
    }

    private static Document activoConCodigos(List<String> hashes) {
        return new Document("activo", true).append("secretoCifrado", CIFRADO_A)
                .append("activadoEn", Date.from(AHORA)).append("ultimoPaso", 100L)
                .append("codigosRecuperacion", hashes.stream().map(h -> codigo(h, false)).toList());
    }

    private ObjectId crearUsuario(Document dosFactores) {
        ObjectId id = new ObjectId();
        Document usuario = new Document("_id", id)
                .append("email", PREFIJO_EMAIL + UUID.randomUUID() + "@ejemplo.es")
                .append("password", "hash-de-la-contrasena")
                .append("estado", "ACTIVO")
                .append("rol", List.of("CLIENTE"))
                .append("perfil", new Document("nombre", "Ana").append("apellidos", "Garcia"))
                .append("contrasenaCambiadaEn", Date.from(AHORA.minusSeconds(3600)));
        if (dosFactores != null) {
            usuario.append("dosFactores", dosFactores);
        }
        usuarios.insertOne(usuario);
        return id;
    }

    private ObjectId crearUsuarioSin2fa() {
        return crearUsuario(null);
    }

    private ObjectId crearUsuarioActivo(List<String> hashes) {
        return crearUsuario(activoConCodigos(hashes));
    }

    private ObjectId crearUsuarioConPendiente(String secreto, Instant creadoEn) {
        return crearUsuario(new Document("activo", false)
                .append("pendiente", new Document("secretoCifrado", secreto).append("creadoEn", Date.from(creadoEn))));
    }

    private Document documento(ObjectId id) {
        return usuarios.find(Filters.eq("_id", id)).first();
    }

    private Document dosFactoresDe(ObjectId id) {
        return documento(id).get("dosFactores", Document.class);
    }

    /** El documento sin el subdocumento dosFactores: lo que estas operaciones no deben tocar. */
    private Document sinDosFactores(ObjectId id) {
        Document documento = documento(id);
        documento.remove("dosFactores");
        return documento;
    }

    /** Lanza todas las tareas a la vez (con una barrera) y devuelve sus resultados. */
    private static <T> List<T> enParalelo(List<Callable<T>> tareas) throws Exception {
        ExecutorService ejecutor = Executors.newFixedThreadPool(tareas.size());
        try {
            CyclicBarrier barrera = new CyclicBarrier(tareas.size());
            List<Future<T>> futuros = new ArrayList<>();
            for (Callable<T> tarea : tareas) {
                futuros.add(ejecutor.submit(() -> {
                    barrera.await(10, TimeUnit.SECONDS);
                    return tarea.call();
                }));
            }
            List<T> resultados = new ArrayList<>();
            for (Future<T> futuro : futuros) {
                resultados.add(futuro.get(30, TimeUnit.SECONDS));
            }
            return resultados;
        } finally {
            ejecutor.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ guardarActivacionPendiente

    @Test
    void guardarActivacionPendiente_enUnaCuentaSinDosFactores_guardaElSecretoPendienteYNadaMas() { // CP-2FA-127
        ObjectId id = crearUsuarioSin2fa();
        Document antes = sinDosFactores(id);

        boolean guardado = repositorio.guardarActivacionPendiente(id.toHexString(), CIFRADO_A, AHORA);

        assertThat(guardado).isTrue();
        Document dosFactores = dosFactoresDe(id);
        assertThat(dosFactores.getBoolean("activo")).isFalse();
        assertThat(dosFactores.get("pendiente", Document.class).getString("secretoCifrado")).isEqualTo(CIFRADO_A);
        assertThat(dosFactores.get("pendiente", Document.class).getDate("creadoEn")).isEqualTo(Date.from(AHORA));
        assertThat(dosFactores).containsOnlyKeys("activo", "pendiente");
        // el resto del usuario (email, contrasena, estado, perfil, contrasenaCambiadaEn) intacto
        assertThat(sinDosFactores(id)).isEqualTo(antes);
    }

    @Test
    void guardarActivacionPendiente_repetida_sustituyeAlSecretoPendienteAnterior() { // CP-2FA-128
        ObjectId id = crearUsuarioSin2fa();
        repositorio.guardarActivacionPendiente(id.toHexString(), CIFRADO_A, AHORA);

        boolean guardado = repositorio.guardarActivacionPendiente(id.toHexString(), CIFRADO_B, AHORA.plusSeconds(30));

        assertThat(guardado).isTrue();
        Document pendiente = dosFactoresDe(id).get("pendiente", Document.class);
        assertThat(pendiente.getString("secretoCifrado")).isEqualTo(CIFRADO_B);
        assertThat(pendiente.getDate("creadoEn")).isEqualTo(Date.from(AHORA.plusSeconds(30)));
    }

    @Test
    void guardarActivacionPendiente_enUnaCuentaConDosFactoresActivo_noHaceNada() { // CP-2FA-129
        ObjectId id = crearUsuarioActivo(hashes(3));
        Document antes = documento(id);

        boolean guardado = repositorio.guardarActivacionPendiente(id.toHexString(), CIFRADO_B, AHORA);

        assertThat(guardado).isFalse();
        assertThat(documento(id)).isEqualTo(antes);
    }

    @Test
    void guardarActivacionPendiente_usuarioInexistente_devuelveFalso() { // CP-2FA-130
        assertThat(repositorio.guardarActivacionPendiente(new ObjectId().toHexString(), CIFRADO_A, AHORA)).isFalse();
    }

    @ParameterizedTest(name = "id \"{0}\"")
    @NullAndEmptySource
    @ValueSource(strings = { " ", "no-es-un-id", "665f1c2e9b1d4a3c8e7f0a1", "665f1c2e9b1d4a3c8e7f0a11a", "{\"$ne\":null}" })
    void guardarActivacionPendiente_idQueNoEsUnObjectId_lanzaExcepcion(String id) { // CP-2FA-131
        assertThrows(IllegalArgumentException.class, () -> repositorio.guardarActivacionPendiente(id, CIFRADO_A, AHORA));
    }

    @Test
    void guardarActivacionPendiente_secretoQueNoEsTextoCifradoOSinFecha_lanzaExcepcion() { // CP-2FA-131
        String id = new ObjectId().toHexString();

        assertThrows(IllegalArgumentException.class,
                () -> repositorio.guardarActivacionPendiente(id, "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", AHORA));
        assertThrows(IllegalArgumentException.class, () -> repositorio.guardarActivacionPendiente(id, null, AHORA));
        assertThrows(IllegalArgumentException.class, () -> repositorio.guardarActivacionPendiente(id, "", AHORA));
        assertThrows(IllegalArgumentException.class, () -> repositorio.guardarActivacionPendiente(id, CIFRADO_A, null));
    }

    // ------------------------------------------------------------------ activar

    @Test
    void activar_conElSecretoPendienteVigente_dejaElDosFactoresActivo() { // CP-2FA-132
        ObjectId id = crearUsuarioConPendiente(CIFRADO_A, AHORA);
        Document antes = sinDosFactores(id);
        List<String> hashes = hashes(10);

        boolean activado = repositorio.activar(id.toHexString(), CIFRADO_A, 58_000_000L, hashes,
                AHORA.plusSeconds(60), CADUCIDAD);

        assertThat(activado).isTrue();
        Document dosFactores = dosFactoresDe(id);
        assertThat(dosFactores.getBoolean("activo")).isTrue();
        assertThat(dosFactores.getString("secretoCifrado")).isEqualTo(CIFRADO_A);
        assertThat(dosFactores.getDate("activadoEn")).isEqualTo(Date.from(AHORA.plusSeconds(60)));
        assertThat(((Number) dosFactores.get("ultimoPaso")).longValue()).isEqualTo(58_000_000L);
        // el secreto pendiente ya no esta
        assertThat(dosFactores).doesNotContainKey("pendiente");
        // diez codigos, en orden, todos sin usar y solo con el hash
        List<Document> codigos = dosFactores.getList("codigosRecuperacion", Document.class);
        assertThat(codigos).extracting(c -> c.getString("hash")).containsExactlyElementsOf(hashes);
        assertThat(codigos).allSatisfy(c -> {
            assertThat(c.getBoolean("usado")).isFalse();
            assertThat(c).containsOnlyKeys("hash", "usado");
        });
        assertThat(sinDosFactores(id)).isEqualTo(antes);
    }

    @Test
    void activar_sinSecretoPendiente_devuelveFalsoYNoCambiaNada() { // CP-2FA-133
        ObjectId id = crearUsuarioSin2fa();
        Document antes = documento(id);

        boolean activado = repositorio.activar(id.toHexString(), CIFRADO_A, 1, hashes(10), AHORA, CADUCIDAD);

        assertThat(activado).isFalse();
        assertThat(documento(id)).isEqualTo(antes);
    }

    @Test
    void activar_elSecretoPendienteVale_mientrasNoLleguenLosDiezMinutos() { // CP-2FA-134
        ObjectId justoAntes = crearUsuarioConPendiente(CIFRADO_A, AHORA);
        ObjectId justoCuando = crearUsuarioConPendiente(CIFRADO_A, AHORA);

        // A los 9 min 59 s sigue valiendo; a los 10 minutos exactos ya no, como los tokens de recuperacion
        assertThat(repositorio.activar(justoAntes.toHexString(), CIFRADO_A, 1, hashes(10), AHORA.plusSeconds(599), CADUCIDAD))
                .isTrue();
        assertThat(repositorio.activar(justoCuando.toHexString(), CIFRADO_A, 1, hashes(10), AHORA.plusSeconds(600), CADUCIDAD))
                .isFalse();
        // y el caducado queda como estaba: pendiente, sin activar
        assertThat(dosFactoresDe(justoCuando).getBoolean("activo")).isFalse();
        assertThat(dosFactoresDe(justoCuando)).containsKey("pendiente");
    }

    @Test
    void activar_conUnSecretoDistintoAlPendiente_devuelveFalsoYConservaElPendiente() { // CP-2FA-135
        // La persona reinicio la activacion (otro secreto) mientras se verificaba el codigo del primero
        ObjectId id = crearUsuarioConPendiente(CIFRADO_B, AHORA);

        boolean activado = repositorio.activar(id.toHexString(), CIFRADO_A, 1, hashes(10), AHORA, CADUCIDAD);

        assertThat(activado).isFalse();
        Document dosFactores = dosFactoresDe(id);
        assertThat(dosFactores.getBoolean("activo")).isFalse();
        assertThat(dosFactores.get("pendiente", Document.class).getString("secretoCifrado")).isEqualTo(CIFRADO_B);
    }

    @Test
    void activar_enUnaCuentaYaActiva_devuelveFalsoYNoCambiaNada() { // CP-2FA-136
        ObjectId id = crearUsuarioActivo(hashes(3));
        Document antes = documento(id);

        boolean activado = repositorio.activar(id.toHexString(), CIFRADO_A, 5, hashes(10), AHORA, CADUCIDAD);

        assertThat(activado).isFalse();
        assertThat(documento(id)).isEqualTo(antes);
    }

    @Test
    void activar_usuarioInexistente_devuelveFalso() { // CP-2FA-137
        assertThat(repositorio.activar(new ObjectId().toHexString(), CIFRADO_A, 1, hashes(10), AHORA, CADUCIDAD))
                .isFalse();
    }

    @Test
    void activar_datosMalFormados_lanzaExcepcion() { // CP-2FA-138
        String id = new ObjectId().toHexString();
        List<String> bien = hashes(10);

        assertThrows(IllegalArgumentException.class, () -> repositorio.activar("no-es-un-id", CIFRADO_A, 1, bien, AHORA, CADUCIDAD));
        assertThrows(IllegalArgumentException.class, () -> repositorio.activar(id, "GEZDGNBVGY3TQOJQ", 1, bien, AHORA, CADUCIDAD));
        assertThrows(IllegalArgumentException.class, () -> repositorio.activar(id, null, 1, bien, AHORA, CADUCIDAD));
        assertThrows(IllegalArgumentException.class, () -> repositorio.activar(id, CIFRADO_A, -1, bien, AHORA, CADUCIDAD));
        assertThrows(IllegalArgumentException.class, () -> repositorio.activar(id, CIFRADO_A, 1, null, AHORA, CADUCIDAD));
        assertThrows(IllegalArgumentException.class, () -> repositorio.activar(id, CIFRADO_A, 1, List.of(), AHORA, CADUCIDAD));
        assertThrows(IllegalArgumentException.class, () -> repositorio.activar(id, CIFRADO_A, 1, hashes(21), AHORA, CADUCIDAD));
        assertThrows(IllegalArgumentException.class,
                () -> repositorio.activar(id, CIFRADO_A, 1, List.of("ABCD-EFGH-2345-6723"), AHORA, CADUCIDAD));
        assertThrows(IllegalArgumentException.class,
                () -> repositorio.activar(id, CIFRADO_A, 1, List.of(bien.get(0), bien.get(0)), AHORA, CADUCIDAD));
        assertThrows(IllegalArgumentException.class, () -> repositorio.activar(id, CIFRADO_A, 1, bien, null, CADUCIDAD));
        assertThrows(IllegalArgumentException.class, () -> repositorio.activar(id, CIFRADO_A, 1, bien, AHORA, null));
        assertThrows(IllegalArgumentException.class, () -> repositorio.activar(id, CIFRADO_A, 1, bien, AHORA, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> repositorio.activar(id, CIFRADO_A, 1, bien, AHORA, Duration.ofSeconds(-1)));
    }

    @Test
    void activar_desdeVariosHilosALaVez_soloUnoLoActiva() throws Exception { // CP-2FA-139
        ObjectId id = crearUsuarioConPendiente(CIFRADO_A, AHORA);
        List<Callable<Boolean>> tareas = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tareas.add(() -> repositorio.activar(id.toHexString(), CIFRADO_A, 1, hashes(10), AHORA, CADUCIDAD));
        }

        List<Boolean> resultados = enParalelo(tareas);

        assertThat(resultados).filteredOn(r -> r).hasSize(1);
        assertThat(dosFactoresDe(id).getList("codigosRecuperacion", Document.class)).hasSize(10);
    }

    // ------------------------------------------------------------------ registrarPasoAceptado

    @Test
    void registrarPasoAceptado_pasoMayorQueElUltimo_loGuarda() { // CP-2FA-140
        ObjectId id = crearUsuarioActivo(hashes(3)); // ultimoPaso = 100

        boolean registrado = repositorio.registrarPasoAceptado(id.toHexString(), 101);

        assertThat(registrado).isTrue();
        assertThat(((Number) dosFactoresDe(id).get("ultimoPaso")).longValue()).isEqualTo(101);
    }

    @Test
    void registrarPasoAceptado_cuandoAunNoHayNingunPaso_aceptaCualquiera() { // CP-2FA-140
        Document sinPaso = activoConCodigos(hashes(3));
        sinPaso.remove("ultimoPaso");
        ObjectId id = crearUsuario(sinPaso);

        assertThat(repositorio.registrarPasoAceptado(id.toHexString(), 0)).isTrue();
    }

    @Test
    void registrarPasoAceptado_pasoIgualOMenor_devuelveFalsoYNoLoCambia() { // CP-2FA-141
        ObjectId id = crearUsuarioActivo(hashes(3)); // ultimoPaso = 100

        assertThat(repositorio.registrarPasoAceptado(id.toHexString(), 100)).isFalse();
        assertThat(repositorio.registrarPasoAceptado(id.toHexString(), 99)).isFalse();
        assertThat(repositorio.registrarPasoAceptado(id.toHexString(), 0)).isFalse();
        assertThat(((Number) dosFactoresDe(id).get("ultimoPaso")).longValue()).isEqualTo(100);
    }

    @Test
    void registrarPasoAceptado_siElDosFactoresNoEstaActivo_devuelveFalsoYNoCreaNada() { // CP-2FA-142
        ObjectId sin2fa = crearUsuarioSin2fa();
        ObjectId pendiente = crearUsuarioConPendiente(CIFRADO_A, AHORA);
        Document antesSin = documento(sin2fa);
        Document antesPendiente = documento(pendiente);

        assertThat(repositorio.registrarPasoAceptado(sin2fa.toHexString(), 5)).isFalse();
        assertThat(repositorio.registrarPasoAceptado(pendiente.toHexString(), 5)).isFalse();
        assertThat(repositorio.registrarPasoAceptado(new ObjectId().toHexString(), 5)).isFalse();
        assertThat(documento(sin2fa)).isEqualTo(antesSin);
        assertThat(documento(pendiente)).isEqualTo(antesPendiente);
    }

    @Test
    void registrarPasoAceptado_elMismoPasoDesdeVariosHilos_soloUnoGana() throws Exception { // CP-2FA-143
        // Es el caso de dos peticiones simultaneas con el mismo codigo TOTP: solo una puede entrar
        ObjectId id = crearUsuarioActivo(hashes(3));
        List<Callable<Boolean>> tareas = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            tareas.add(() -> repositorio.registrarPasoAceptado(id.toHexString(), 58_000_000L));
        }

        List<Boolean> resultados = enParalelo(tareas);

        assertThat(resultados).filteredOn(r -> r).hasSize(1);
    }

    @Test
    void registrarPasoAceptado_pasosDistintosALaVez_nuncaRetrocede() throws Exception { // CP-2FA-144
        ObjectId id = crearUsuarioActivo(hashes(3)); // ultimoPaso = 100
        List<Callable<Boolean>> tareas = new ArrayList<>();
        for (int paso = 101; paso <= 120; paso++) {
            long esteMismo = paso;
            tareas.add(() -> repositorio.registrarPasoAceptado(id.toHexString(), esteMismo));
        }

        enParalelo(tareas);

        // Gane quien gane, el valor final es el mayor: un paso viejo no puede pisar a uno nuevo
        assertThat(((Number) dosFactoresDe(id).get("ultimoPaso")).longValue()).isEqualTo(120);
    }

    @Test
    void registrarPasoAceptado_aceptaPasosMayoresQueUnEnteroDe32Bits() { // CP-2FA-145
        ObjectId id = crearUsuarioActivo(hashes(3));

        assertThat(repositorio.registrarPasoAceptado(id.toHexString(), 3_000_000_000L)).isTrue();
        assertThat(((Number) dosFactoresDe(id).get("ultimoPaso")).longValue()).isEqualTo(3_000_000_000L);
    }

    @Test
    void registrarPasoAceptado_datosMalFormados_lanzaExcepcion() { // CP-2FA-145
        String id = new ObjectId().toHexString();

        assertThrows(IllegalArgumentException.class, () -> repositorio.registrarPasoAceptado(id, -1));
        assertThrows(IllegalArgumentException.class, () -> repositorio.registrarPasoAceptado("no-es-un-id", 5));
        assertThrows(IllegalArgumentException.class, () -> repositorio.registrarPasoAceptado(null, 5));
    }

    // ------------------------------------------------------------------ consumirCodigoRecuperacion

    @Test
    void consumirCodigoRecuperacion_codigoSinUsar_loMarcaComoUsado() { // CP-2FA-146
        List<String> hashes = hashes(5);
        ObjectId id = crearUsuarioActivo(hashes);

        boolean consumido = repositorio.consumirCodigoRecuperacion(id.toHexString(), hashes.get(2), AHORA.plusSeconds(5));

        assertThat(consumido).isTrue();
        List<Document> codigos = dosFactoresDe(id).getList("codigosRecuperacion", Document.class);
        assertThat(codigos.get(2).getBoolean("usado")).isTrue();
        assertThat(codigos.get(2).getDate("usadoEn")).isEqualTo(Date.from(AHORA.plusSeconds(5)));
        // los demas siguen sin usar y en su sitio
        assertThat(codigos).extracting(c -> c.getString("hash")).containsExactlyElementsOf(hashes);
        assertThat(codigos).filteredOn(c -> !c.getBoolean("usado")).hasSize(4);
    }

    @Test
    void consumirCodigoRecuperacion_elMismoCodigoDosVeces_laSegundaFalla() { // CP-2FA-147
        List<String> hashes = hashes(5);
        ObjectId id = crearUsuarioActivo(hashes);
        repositorio.consumirCodigoRecuperacion(id.toHexString(), hashes.get(0), AHORA);
        Document antes = dosFactoresDe(id);

        boolean otraVez = repositorio.consumirCodigoRecuperacion(id.toHexString(), hashes.get(0), AHORA.plusSeconds(60));

        assertThat(otraVez).isFalse();
        // y no cambia la fecha de uso del primero
        assertThat(dosFactoresDe(id)).isEqualTo(antes);
    }

    @Test
    void consumirCodigoRecuperacion_hashQueNoEstaEntreLosCodigos_devuelveFalso() { // CP-2FA-147
        ObjectId id = crearUsuarioActivo(hashes(5));
        Document antes = dosFactoresDe(id);

        assertThat(repositorio.consumirCodigoRecuperacion(id.toHexString(), "f".repeat(64), AHORA)).isFalse();
        assertThat(dosFactoresDe(id)).isEqualTo(antes);
    }

    @Test
    void consumirCodigoRecuperacion_elCodigoDeOtroUsuario_devuelveFalso() { // CP-2FA-148
        List<String> hashesDeLaOtra = IntStream.rangeClosed(101, 105).mapToObj(i -> String.format("%064x", i)).toList();
        ObjectId ana = crearUsuarioActivo(hashes(5));
        ObjectId luis = crearUsuarioActivo(hashesDeLaOtra);

        assertThat(repositorio.consumirCodigoRecuperacion(ana.toHexString(), hashesDeLaOtra.get(0), AHORA)).isFalse();
        // y el de Luis sigue sin usar
        assertThat(dosFactoresDe(luis).getList("codigosRecuperacion", Document.class))
                .allSatisfy(c -> assertThat(c.getBoolean("usado")).isFalse());
    }

    @Test
    void consumirCodigoRecuperacion_siElDosFactoresNoEstaActivo_devuelveFalso() { // CP-2FA-149
        ObjectId sin2fa = crearUsuarioSin2fa();
        ObjectId pendiente = crearUsuarioConPendiente(CIFRADO_A, AHORA);

        assertThat(repositorio.consumirCodigoRecuperacion(sin2fa.toHexString(), hashes(1).get(0), AHORA)).isFalse();
        assertThat(repositorio.consumirCodigoRecuperacion(pendiente.toHexString(), hashes(1).get(0), AHORA)).isFalse();
        assertThat(repositorio.consumirCodigoRecuperacion(new ObjectId().toHexString(), hashes(1).get(0), AHORA)).isFalse();
    }

    @Test
    void consumirCodigoRecuperacion_elMismoCodigoDesdeVariosHilos_soloUnoGana() throws Exception { // CP-2FA-150
        List<String> hashes = hashes(5);
        ObjectId id = crearUsuarioActivo(hashes);
        List<Callable<Boolean>> tareas = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            tareas.add(() -> repositorio.consumirCodigoRecuperacion(id.toHexString(), hashes.get(1), AHORA));
        }

        List<Boolean> resultados = enParalelo(tareas);

        assertThat(resultados).filteredOn(r -> r).hasSize(1);
        assertThat(dosFactoresDe(id).getList("codigosRecuperacion", Document.class))
                .filteredOn(c -> c.getBoolean("usado")).hasSize(1);
    }

    @Test
    void consumirCodigoRecuperacion_codigosDistintosALaVez_todosSeConsumen() throws Exception { // CP-2FA-151
        List<String> hashes = hashes(10);
        ObjectId id = crearUsuarioActivo(hashes);
        List<Callable<Boolean>> tareas = new ArrayList<>();
        for (String hash : hashes) {
            tareas.add(() -> repositorio.consumirCodigoRecuperacion(id.toHexString(), hash, AHORA));
        }

        List<Boolean> resultados = enParalelo(tareas);

        // Marcar uno no pisa la marca de otro
        assertThat(resultados).allMatch(r -> r);
        assertThat(dosFactoresDe(id).getList("codigosRecuperacion", Document.class))
                .allSatisfy(c -> assertThat(c.getBoolean("usado")).isTrue());
    }

    @Test
    void consumirCodigoRecuperacion_datosMalFormados_lanzaExcepcion() { // CP-2FA-152
        String id = new ObjectId().toHexString();

        assertThrows(IllegalArgumentException.class, () -> repositorio.consumirCodigoRecuperacion("no-es-un-id", hashes(1).get(0), AHORA));
        assertThrows(IllegalArgumentException.class, () -> repositorio.consumirCodigoRecuperacion(id, null, AHORA));
        assertThrows(IllegalArgumentException.class, () -> repositorio.consumirCodigoRecuperacion(id, "ABCD-EFGH-2345-6723", AHORA));
        assertThrows(IllegalArgumentException.class, () -> repositorio.consumirCodigoRecuperacion(id, "F".repeat(64), AHORA));
        assertThrows(IllegalArgumentException.class, () -> repositorio.consumirCodigoRecuperacion(id, "{\"$ne\":null}", AHORA));
        assertThrows(IllegalArgumentException.class, () -> repositorio.consumirCodigoRecuperacion(id, hashes(1).get(0), null));
    }

    // ------------------------------------------------------------------ reemplazarCodigosRecuperacion

    @Test
    void reemplazarCodigosRecuperacion_enUnaCuentaActiva_sustituyeTodosLosCodigos() { // CP-2FA-153
        List<String> antiguos = hashes(10);
        ObjectId id = crearUsuarioActivo(antiguos);
        repositorio.consumirCodigoRecuperacion(id.toHexString(), antiguos.get(0), AHORA);
        Document antes = dosFactoresDe(id);
        List<String> nuevos = IntStream.rangeClosed(201, 208).mapToObj(i -> String.format("%064x", i)).toList();

        boolean reemplazado = repositorio.reemplazarCodigosRecuperacion(id.toHexString(), nuevos);

        assertThat(reemplazado).isTrue();
        Document dosFactores = dosFactoresDe(id);
        List<Document> codigos = dosFactores.getList("codigosRecuperacion", Document.class);
        assertThat(codigos).extracting(c -> c.getString("hash")).containsExactlyElementsOf(nuevos);
        assertThat(codigos).allSatisfy(c -> assertThat(c.getBoolean("usado")).isFalse());
        // el resto del segundo factor (secreto, ultimo paso, fecha de activacion) no cambia
        assertThat(dosFactores.getString("secretoCifrado")).isEqualTo(antes.getString("secretoCifrado"));
        assertThat(dosFactores.get("ultimoPaso")).isEqualTo(antes.get("ultimoPaso"));
        assertThat(dosFactores.getDate("activadoEn")).isEqualTo(antes.getDate("activadoEn"));
        // y los antiguos ya no sirven
        assertThat(repositorio.consumirCodigoRecuperacion(id.toHexString(), antiguos.get(1), AHORA)).isFalse();
        assertThat(repositorio.consumirCodigoRecuperacion(id.toHexString(), nuevos.get(0), AHORA)).isTrue();
    }

    @Test
    void reemplazarCodigosRecuperacion_siElDosFactoresNoEstaActivo_devuelveFalsoYNoCambiaNada() { // CP-2FA-154
        ObjectId sin2fa = crearUsuarioSin2fa();
        ObjectId pendiente = crearUsuarioConPendiente(CIFRADO_A, AHORA);
        Document antesSin = documento(sin2fa);
        Document antesPendiente = documento(pendiente);

        assertThat(repositorio.reemplazarCodigosRecuperacion(sin2fa.toHexString(), hashes(10))).isFalse();
        assertThat(repositorio.reemplazarCodigosRecuperacion(pendiente.toHexString(), hashes(10))).isFalse();
        assertThat(repositorio.reemplazarCodigosRecuperacion(new ObjectId().toHexString(), hashes(10))).isFalse();
        assertThat(documento(sin2fa)).isEqualTo(antesSin);
        assertThat(documento(pendiente)).isEqualTo(antesPendiente);
    }

    @Test
    void reemplazarCodigosRecuperacion_listasMalFormadas_lanzaExcepcion() { // CP-2FA-155
        String id = new ObjectId().toHexString();

        assertThrows(IllegalArgumentException.class, () -> repositorio.reemplazarCodigosRecuperacion("no-es-un-id", hashes(10)));
        assertThrows(IllegalArgumentException.class, () -> repositorio.reemplazarCodigosRecuperacion(id, null));
        assertThrows(IllegalArgumentException.class, () -> repositorio.reemplazarCodigosRecuperacion(id, List.of()));
        assertThrows(IllegalArgumentException.class, () -> repositorio.reemplazarCodigosRecuperacion(id, hashes(21)));
        assertThrows(IllegalArgumentException.class,
                () -> repositorio.reemplazarCodigosRecuperacion(id, List.of("ABCD-EFGH-2345-6723")));
        assertThrows(IllegalArgumentException.class,
                () -> repositorio.reemplazarCodigosRecuperacion(id, List.of(hashes(1).get(0), hashes(1).get(0))));
    }

    // ------------------------------------------------------------------ lectura con Spring Data

    @Test
    void unUsuarioDeAntesDeLaHdu_seLeeSinDosFactores() { // CP-2FA-160
        ObjectId id = crearUsuarioSin2fa();

        Usuario usuario = repositorioUsuario.findById(id.toHexString()).orElseThrow();

        assertThat(usuario.getDosFactores()).isNull();
        assertThat(usuario.tieneDosFactoresActivo()).isFalse();
    }

    @Test
    void unUsuarioConDosFactoresActivo_seLeeConTodosSusCampos() { // CP-2FA-161
        Document activo = new Document("activo", true).append("secretoCifrado", CIFRADO_A)
                .append("activadoEn", Date.from(AHORA)).append("ultimoPaso", 58_000_000L)
                .append("codigosRecuperacion", List.of(codigo(hashes(2).get(0), false), codigo(hashes(2).get(1), true)));
        ObjectId id = crearUsuario(activo);

        Usuario usuario = repositorioUsuario.findById(id.toHexString()).orElseThrow();

        assertThat(usuario.tieneDosFactoresActivo()).isTrue();
        DosFactores dosFactores = usuario.getDosFactores();
        assertThat(dosFactores.isActivo()).isTrue();
        assertThat(dosFactores.getSecretoCifrado()).isEqualTo(CIFRADO_A);
        assertThat(dosFactores.getActivadoEn()).isEqualTo(AHORA);
        assertThat(dosFactores.getUltimoPaso()).isEqualTo(58_000_000L);
        assertThat(dosFactores.getCodigosRecuperacion()).extracting(DosFactores.CodigoRecuperacion::getHash)
                .containsExactly(hashes(2).get(0), hashes(2).get(1));
        assertThat(dosFactores.getCodigosRecuperacion()).extracting(DosFactores.CodigoRecuperacion::isUsado)
                .containsExactly(false, true);
        assertThat(dosFactores.getCodigosRecuperacion().get(1).getUsadoEn()).isEqualTo(AHORA);
        assertThat(dosFactores.codigosDisponibles()).isEqualTo(1);
    }

    @Test
    void unUsuarioConUnaActivacionPendiente_seLeeComoNoActivo() { // CP-2FA-162
        ObjectId id = crearUsuarioConPendiente(CIFRADO_A, AHORA);

        Usuario usuario = repositorioUsuario.findById(id.toHexString()).orElseThrow();

        assertThat(usuario.tieneDosFactoresActivo()).isFalse();
        assertThat(usuario.getDosFactores().getPendiente().getSecretoCifrado()).isEqualTo(CIFRADO_A);
        assertThat(usuario.getDosFactores().getPendiente().getCreadoEn()).isEqualTo(AHORA);
    }

    @Test
    void guardarUnUsuarioLeido_conservaSuDosFactoresIntactoYValidoParaElEsquema() { // CP-2FA-163
        // Spring Data guarda el documento entero (p. ej. al bloquear o modificar un usuario): no debe estropear el 2FA
        ObjectId id = crearUsuarioActivo(hashes(10));
        repositorio.consumirCodigoRecuperacion(id.toHexString(), hashes(10).get(3), AHORA);
        Document antes = documento(id);
        Usuario usuario = repositorioUsuario.findById(id.toHexString()).orElseThrow();

        repositorioUsuario.save(usuario);

        assertThat(documento(id)).isEqualTo(antes);
    }

    @Test
    void guardarUnUsuarioConActivacionPendiente_loConservaIntacto() { // CP-2FA-164
        ObjectId id = crearUsuarioConPendiente(CIFRADO_A, AHORA);
        Document antes = documento(id);

        repositorioUsuario.save(repositorioUsuario.findById(id.toHexString()).orElseThrow());

        assertThat(documento(id)).isEqualTo(antes);
    }

    @Test
    void guardarUnUsuarioDeAntesDeLaHdu_noLeAnadeDosFactores() { // CP-2FA-165
        ObjectId id = crearUsuarioSin2fa();
        Document antes = documento(id);

        repositorioUsuario.save(repositorioUsuario.findById(id.toHexString()).orElseThrow());

        assertThat(documento(id)).isEqualTo(antes).doesNotContainKey("dosFactores");
    }

    @Test
    void elDocumentoQueEscribenLasOperacionesLoLeeSpringData() { // CP-2FA-166
        ObjectId id = crearUsuarioSin2fa();
        repositorio.guardarActivacionPendiente(id.toHexString(), CIFRADO_A, AHORA);
        repositorio.activar(id.toHexString(), CIFRADO_A, 9, hashes(10), AHORA.plusSeconds(10), CADUCIDAD);
        repositorio.registrarPasoAceptado(id.toHexString(), 10);
        repositorio.consumirCodigoRecuperacion(id.toHexString(), hashes(10).get(0), AHORA.plusSeconds(20));

        DosFactores dosFactores = repositorioUsuario.findById(id.toHexString()).orElseThrow().getDosFactores();

        assertThat(dosFactores.isActivo()).isTrue();
        assertThat(dosFactores.getUltimoPaso()).isEqualTo(10);
        assertThat(dosFactores.getActivadoEn()).isEqualTo(AHORA.plusSeconds(10));
        assertThat(dosFactores.codigosDisponibles()).isEqualTo(9);
        assertThat(dosFactores.getPendiente()).isNull();
    }
}
