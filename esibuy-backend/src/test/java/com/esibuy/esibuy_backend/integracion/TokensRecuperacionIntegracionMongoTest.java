package com.esibuy.esibuy_backend.integracion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.esibuy.esibuy_backend.servicio.GeneradorTokens;
import com.esibuy.esibuy_backend.servicio.ResultadoToken;
import com.esibuy.esibuy_backend.servicio.ResultadoToken.Motivo;
import com.esibuy.esibuy_backend.servicio.ServicioTokensRecuperacion;
import com.esibuy.esibuy_backend.servicio.TokenEmitido;
import com.esibuy.esibuy_backend.util.RelojMutable;
import com.mongodb.client.MongoCollection;

/**
 * Tokens de recuperacion de contrasena contra MongoDB real (HdU recuperacion de contrasena, fase 1).
 * Casos: CP-TOK-10 a CP-TOK-37.
 *
 * Hace falta MongoDB real (y no mocks) porque lo que se prueba es justo lo que los mocks no reproducen: que en la
 * coleccion solo queda el hash, que el consumo es atomico frente a peticiones simultaneas y que las validaciones del
 * esquema del script de BBDD aceptan lo que escribe la aplicacion.
 *
 * Reglas que fijan:
 *  - Un token vale mientras ahora < caducaEn (a los 15 minutos exactos ya no).
 *  - Solo hay un token vigente por usuario: emitir uno nuevo anula el anterior.
 *  - Un token solo se puede consumir una vez, incluso con peticiones concurrentes.
 *  - El motivo de rechazo (desconocido, caducado, ya usado, invalidado) es para la auditoria; el estado USADO tiene
 *    prioridad sobre CADUCADO porque reutilizar un enlace ya gastado es una alerta de seguridad.
 *  - Un valor que no tiene forma de token ni llega a consultarse en la base de datos.
 *
 * Terminada: ya no lleva la etiqueta pendiente-recuperacion y entra en la regresion.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class TokensRecuperacionIntegracionMongoTest extends IntegracionLoginBase {

    private static final RelojMutable RELOJ =
            new RelojMutable(Instant.parse("2026-10-16T10:00:00Z"), ZoneId.of("Europe/Madrid"));

    /** Sustituye el reloj de la aplicacion por uno que solo avanza cuando la prueba lo pide. */
    @TestConfiguration
    static class ConfiguracionDeReloj {
        @Bean
        @Primary
        Clock relojDePrueba() {
            return RELOJ;
        }
    }

    @Autowired
    private ServicioTokensRecuperacion servicio;
    @Autowired
    private MongoTemplate mongo;

    private MongoCollection<Document> tokens;
    private String idUsuario;

    @BeforeEach
    void prepararColeccion() {
        tokens = mongo.getCollection("tokens_recuperacion");
        tokens.deleteMany(new Document());
        idUsuario = new ObjectId().toHexString();
    }

    private Document documentoDe(String token) {
        return tokens.find(new Document("hashToken", GeneradorTokens.hash(token))).first();
    }

    // ------------------------------------------------------------------ emitir

    @Test
    void emitir_devuelveUnTokenBase64UrlConCaducidadDeQuinceMinutos() { // CP-TOK-10
        // When
        TokenEmitido emitido = servicio.emitir(idUsuario);

        // Then
        assertThat(emitido.token()).matches("^[A-Za-z0-9_-]{43}$");
        assertThat(emitido.creadoEn()).isEqualTo(RELOJ.instant());
        assertThat(emitido.caducaEn()).isEqualTo(RELOJ.instant().plus(Duration.ofMinutes(15)));
        assertThat(ServicioTokensRecuperacion.VALIDEZ).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void emitir_enLaBaseDeDatosSoloQuedaElHashNuncaElToken() { // CP-TOK-11
        // When
        TokenEmitido emitido = servicio.emitir(idUsuario);

        // Then: un unico documento, con el hash y el resto de datos, y el token en claro no aparece en ninguna parte
        assertThat(tokens.countDocuments()).isEqualTo(1);
        Document documento = tokens.find().first();
        assertThat(documento.getString("hashToken")).isEqualTo(GeneradorTokens.hash(emitido.token()));
        assertThat(documento.toJson()).doesNotContain(emitido.token());
        assertThat(documento.get("idUsuario")).isEqualTo(new ObjectId(idUsuario));
        assertThat(documento.getString("estado")).isEqualTo("VIGENTE");
        assertThat(documento.getDate("creadoEn")).isEqualTo(Date.from(RELOJ.instant()));
        assertThat(documento.getDate("caducaEn")).isEqualTo(Date.from(RELOJ.instant().plus(Duration.ofMinutes(15))));
        assertThat(documento).doesNotContainKeys("usadoEn", "token", "_class");
    }

    @Test
    void emitir_unaSegundaVez_anulaElTokenAnterior() { // CP-TOK-12
        // Given
        TokenEmitido primero = servicio.emitir(idUsuario);

        // When
        TokenEmitido segundo = servicio.emitir(idUsuario);

        // Then: solo el nuevo sigue vigente
        assertThat(servicio.validar(primero.token()).motivo()).isEqualTo(Motivo.INVALIDADO);
        assertThat(servicio.validar(segundo.token()).motivo()).isEqualTo(Motivo.VALIDO);
        assertThat(documentoDe(primero.token()).getString("estado")).isEqualTo("INVALIDADO");
        assertThat(tokens.countDocuments(new Document("idUsuario", new ObjectId(idUsuario)).append("estado", "VIGENTE")))
                .isEqualTo(1);
    }

    @Test
    void emitir_noAfectaALosTokensDeOtrosUsuarios() { // CP-TOK-13
        // Given
        TokenEmitido deOtro = servicio.emitir(new ObjectId().toHexString());

        // When
        servicio.emitir(idUsuario);

        // Then
        assertThat(servicio.validar(deOtro.token()).motivo()).isEqualTo(Motivo.VALIDO);
    }

    @ParameterizedTest(name = "id de usuario no valido: [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = { " ", "no-es-un-objectid", "123", "{ \"$ne\": null }", "zzzzzzzzzzzzzzzzzzzzzzzz" })
    void emitir_idDeUsuarioQueNoEsUnObjectId_seRechaza(String id) { // CP-TOK-14
        assertThrows(IllegalArgumentException.class, () -> servicio.emitir(id));
        assertThat(tokens.countDocuments()).isZero();
    }

    // ------------------------------------------------------------------ validar

    @Test
    void validar_tokenVigente_devuelveElUsuarioSinCambiarNada() { // CP-TOK-20
        // Given
        TokenEmitido emitido = servicio.emitir(idUsuario);
        Document antes = documentoDe(emitido.token());

        // When
        ResultadoToken primera = servicio.validar(emitido.token());
        ResultadoToken segunda = servicio.validar(emitido.token());

        // Then: validar es de solo lectura, se puede repetir
        assertThat(primera.esValido()).isTrue();
        assertThat(primera.idUsuario()).isEqualTo(idUsuario);
        assertThat(segunda).isEqualTo(primera);
        assertThat(documentoDe(emitido.token())).isEqualTo(antes);
    }

    @Test
    void validar_justoAntesDeLaCaducidadVale_yEnElInstanteExactoYaNo() { // CP-TOK-21
        // Given
        TokenEmitido emitido = servicio.emitir(idUsuario);

        // When / Then: a los 14 min 59 s todavia vale...
        RELOJ.avanzar(Duration.ofMinutes(14).plusSeconds(59));
        assertThat(servicio.validar(emitido.token()).motivo()).isEqualTo(Motivo.VALIDO);

        // ...y a los 15 minutos exactos ya esta caducado
        RELOJ.avanzar(Duration.ofSeconds(1));
        ResultadoToken caducado = servicio.validar(emitido.token());
        assertThat(caducado.motivo()).isEqualTo(Motivo.CADUCADO);
        assertThat(caducado.idUsuario()).isNull();
    }

    @Test
    void validar_tokenQueNoExiste_esDesconocido() { // CP-TOK-22
        // Given: un token con la forma correcta, pero que nadie emitio
        String inventado = new GeneradorTokens().nuevoToken();

        // When
        ResultadoToken resultado = servicio.validar(inventado);

        // Then
        assertThat(resultado.motivo()).isEqualTo(Motivo.DESCONOCIDO);
        assertThat(resultado.idUsuario()).isNull();
    }

    @ParameterizedTest(name = "valor sin forma de token: [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {
            " ",
            "corto",
            "con espacios en medio de un valor largo que no es un token 0123456789",
            "{ \"$ne\": null }",
            "{$gt: ''}",
            "../../etc/passwd-0123456789abcdefghijklmnopqrstuvwxyz",
            "token\r\ncon-saltos-de-linea-0123456789abcdefghijklmnopqrstuvwxyz",
            "Zk3-Qw9_Lm2xPa7RtYb4NcV8HdS1JfGe6UoIi0KuXyA=",
            "Zk3-Qw9_Lm2xPa7RtYb4NcV8HdS1JfGe6UoIi0KuXyAA" })
    void validarYConsumir_valorSinFormaDeToken_esDesconocidoSinLanzarExcepcion(String valor) { // CP-TOK-23
        assertThat(servicio.validar(valor).motivo()).isEqualTo(Motivo.DESCONOCIDO);
        assertThat(servicio.consumir(valor).motivo()).isEqualTo(Motivo.DESCONOCIDO);
    }

    @Test
    void validarYConsumir_valorEnormeSeDescartaSinConsultarNiFallar() { // CP-TOK-23
        String enorme = "a".repeat(100_000);

        assertThat(servicio.validar(enorme).motivo()).isEqualTo(Motivo.DESCONOCIDO);
        assertThat(servicio.consumir(enorme).motivo()).isEqualTo(Motivo.DESCONOCIDO);
    }

    @Test
    void validar_tokenYaUsado_esYaUsadoAunqueTambienHayaCaducado() { // CP-TOK-24
        // Given
        TokenEmitido emitido = servicio.emitir(idUsuario);
        servicio.consumir(emitido.token());
        assertThat(servicio.validar(emitido.token()).motivo()).isEqualTo(Motivo.YA_USADO);

        // When: pasa el tiempo y ademas caduca
        RELOJ.avanzar(Duration.ofHours(2));

        // Then: el estado USADO prevalece, porque reutilizar un enlace gastado es una alerta de seguridad
        assertThat(servicio.validar(emitido.token()).motivo()).isEqualTo(Motivo.YA_USADO);
    }

    // ------------------------------------------------------------------ consumir

    @Test
    void consumir_tokenValido_devuelveElUsuarioYLoMarcaComoUsado() { // CP-TOK-30
        // Given
        TokenEmitido emitido = servicio.emitir(idUsuario);

        // When
        ResultadoToken resultado = servicio.consumir(emitido.token());

        // Then
        assertThat(resultado.esValido()).isTrue();
        assertThat(resultado.idUsuario()).isEqualTo(idUsuario);
        Document documento = documentoDe(emitido.token());
        assertThat(documento.getString("estado")).isEqualTo("USADO");
        assertThat(documento.getDate("usadoEn")).isEqualTo(Date.from(RELOJ.instant()));
    }

    @Test
    void consumir_unaSegundaVez_esYaUsadoSinDevolverElUsuario() { // CP-TOK-31
        // Given
        TokenEmitido emitido = servicio.emitir(idUsuario);
        servicio.consumir(emitido.token());

        // When
        ResultadoToken repetido = servicio.consumir(emitido.token());

        // Then
        assertThat(repetido.motivo()).isEqualTo(Motivo.YA_USADO);
        assertThat(repetido.idUsuario()).isNull();
    }

    @Test
    void consumir_tokenCaducado_seRechazaYNoSeModifica() { // CP-TOK-32
        // Given
        TokenEmitido emitido = servicio.emitir(idUsuario);
        RELOJ.avanzar(Duration.ofMinutes(16));

        // When
        ResultadoToken resultado = servicio.consumir(emitido.token());

        // Then
        assertThat(resultado.motivo()).isEqualTo(Motivo.CADUCADO);
        Document documento = documentoDe(emitido.token());
        assertThat(documento.getString("estado")).isEqualTo("VIGENTE");
        assertThat(documento).doesNotContainKey("usadoEn");
    }

    @Test
    void consumir_tokenAnuladoPorOtraSolicitud_esInvalidado() { // CP-TOK-35
        // Given
        TokenEmitido viejo = servicio.emitir(idUsuario);
        servicio.emitir(idUsuario);

        // When / Then
        assertThat(servicio.consumir(viejo.token()).motivo()).isEqualTo(Motivo.INVALIDADO);
    }

    @Test
    void consumir_ochoPeticionesSimultaneasConElMismoToken_soloUnaGana() throws Exception { // CP-TOK-34
        // Given
        TokenEmitido emitido = servicio.emitir(idUsuario);
        int hilos = 8;
        ExecutorService ejecutor = Executors.newFixedThreadPool(hilos);
        CyclicBarrier salida = new CyclicBarrier(hilos);
        try {
            // When: todas llaman a la vez
            List<Future<ResultadoToken>> resultados = new ArrayList<>();
            for (int i = 0; i < hilos; i++) {
                resultados.add(ejecutor.submit(() -> {
                    salida.await(5, TimeUnit.SECONDS);
                    return servicio.consumir(emitido.token());
                }));
            }

            // Then: exactamente una obtiene el usuario y las demas ven el token ya usado
            long ganadoras = 0;
            long yaUsadas = 0;
            for (Future<ResultadoToken> futuro : resultados) {
                ResultadoToken resultado = futuro.get(10, TimeUnit.SECONDS);
                if (resultado.esValido()) {
                    ganadoras++;
                } else if (resultado.motivo() == Motivo.YA_USADO) {
                    yaUsadas++;
                }
            }
            assertThat(ganadoras).isEqualTo(1);
            assertThat(yaUsadas).isEqualTo(hilos - 1);
        } finally {
            ejecutor.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ invalidarTodos

    @Test
    void invalidarTodos_anulaLosVigentesDelUsuarioYRespetaLosUsadosYLosDeOtros() { // CP-TOK-36
        // Given: un token usado, uno vigente del usuario y uno vigente de otra persona
        TokenEmitido usado = servicio.emitir(idUsuario);
        servicio.consumir(usado.token());
        TokenEmitido vigente = servicio.emitir(idUsuario);
        TokenEmitido deOtro = servicio.emitir(new ObjectId().toHexString());

        // When
        long anulados = servicio.invalidarTodos(idUsuario);

        // Then
        assertThat(anulados).isEqualTo(1);
        assertThat(servicio.validar(vigente.token()).motivo()).isEqualTo(Motivo.INVALIDADO);
        assertThat(servicio.validar(usado.token()).motivo()).isEqualTo(Motivo.YA_USADO);
        assertThat(servicio.validar(deOtro.token()).motivo()).isEqualTo(Motivo.VALIDO);
    }

    @Test
    void invalidarTodos_usuarioSinTokens_devuelveCero() { // CP-TOK-36
        assertThat(servicio.invalidarTodos(idUsuario)).isZero();
    }

    @ParameterizedTest(name = "id de usuario no valido: [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = { "no-es-un-objectid", "{ \"$ne\": null }" })
    void invalidarTodos_idDeUsuarioQueNoEsUnObjectId_seRechaza(String id) { // CP-TOK-37
        assertThrows(IllegalArgumentException.class, () -> servicio.invalidarTodos(id));
    }
}
