package com.esibuy.esibuy_backend.integracion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

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

import com.esibuy.esibuy_backend.repositorio.RepositorioDosFactores;
import com.esibuy.esibuy_backend.servicio.ValidadorSesionUsuario;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;

/**
 * Marca de cambio de credenciales que invalida las sesiones anteriores, contra MongoDB real (HdU 2FA, fase 2).
 * Casos: CP-2FA-266 a CP-2FA-270.
 *
 * Al activar, desactivar o regenerar los codigos del 2FA se anota en el usuario la marca contrasenaCambiadaEn (la misma
 * que usa el restablecimiento de contrasena): las sesiones iniciadas antes dejan de valer. Es una escritura que toca solo
 * ese campo. Una sesion vale solo si empezo estrictamente despues de la marca, comparando en milisegundos.
 *
 * Terminada: ya no lleva la etiqueta pendiente-2fa y entra en la regresion.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class MarcaCredencialesIntegracionMongoTest extends IntegracionLoginBase {

    private static final String PREFIJO_EMAIL = "marca-2fa-";
    private static final Instant AHORA = Instant.parse("2026-10-20T10:00:00Z");

    @Autowired
    private RepositorioDosFactores repositorio;
    @Autowired
    private ValidadorSesionUsuario validadorSesion;
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

    private ObjectId crearUsuario(Date contrasenaCambiadaEn) {
        ObjectId id = new ObjectId();
        Document usuario = new Document("_id", id)
                .append("email", PREFIJO_EMAIL + UUID.randomUUID() + "@ejemplo.es")
                .append("password", "hash-de-la-contrasena")
                .append("estado", "ACTIVO")
                .append("rol", List.of("CLIENTE"))
                .append("perfil", new Document("nombre", "Ana").append("apellidos", "Garcia"));
        if (contrasenaCambiadaEn != null) {
            usuario.append("contrasenaCambiadaEn", contrasenaCambiadaEn);
        }
        usuarios.insertOne(usuario);
        return id;
    }

    private Document documento(ObjectId id) {
        return usuarios.find(Filters.eq("_id", id)).first();
    }

    @Test
    void marcarCredencialesCambiadas_guardaLaMarcaYNoTocaNadaMas() { // CP-2FA-266
        ObjectId id = crearUsuario(null);
        Document antes = documento(id);

        boolean marcado = repositorio.marcarCredencialesCambiadas(id.toHexString(), AHORA);

        assertThat(marcado).isTrue();
        Document despues = documento(id);
        assertThat(despues.getDate("contrasenaCambiadaEn")).isEqualTo(Date.from(AHORA));
        despues.remove("contrasenaCambiadaEn");
        assertThat(despues).isEqualTo(antes);
    }

    @Test
    void marcarCredencialesCambiadas_sustituyeLaMarcaAnterior() { // CP-2FA-267
        ObjectId id = crearUsuario(Date.from(AHORA.minusSeconds(3600)));

        repositorio.marcarCredencialesCambiadas(id.toHexString(), AHORA);

        assertThat(documento(id).getDate("contrasenaCambiadaEn")).isEqualTo(Date.from(AHORA));
    }

    @Test
    void marcarCredencialesCambiadas_usuarioInexistente_devuelveFalso() { // CP-2FA-268
        assertThat(repositorio.marcarCredencialesCambiadas(new ObjectId().toHexString(), AHORA)).isFalse();
    }

    @ParameterizedTest(name = "id \"{0}\"")
    @NullAndEmptySource
    @ValueSource(strings = { " ", "no-es-un-id", "665f1c2e9b1d4a3c8e7f0a1", "{\"$ne\":null}" })
    void marcarCredencialesCambiadas_idMalFormado_lanzaExcepcion(String id) { // CP-2FA-269
        assertThrows(IllegalArgumentException.class, () -> repositorio.marcarCredencialesCambiadas(id, AHORA));
    }

    @Test
    void marcarCredencialesCambiadas_sinInstante_lanzaExcepcion() { // CP-2FA-269
        assertThrows(IllegalArgumentException.class,
                () -> repositorio.marcarCredencialesCambiadas(new ObjectId().toHexString(), null));
    }

    @Test
    void laMarcaInvalidaLasSesionesAnterioresPeroNoUnaIniciadaUnMilisegundoDespues() { // CP-2FA-270
        ObjectId id = crearUsuario(null);
        // La marca va un milisegundo antes del cambio, como la pone el servicio
        repositorio.marcarCredencialesCambiadas(id.toHexString(), AHORA.minusMillis(1));

        // Una sesion anterior queda invalida; una iniciada en el instante del cambio (la renovada) sobrevive
        assertThat(validadorSesion.sigueVigente(id.toHexString(), AHORA.minusMillis(500))).isFalse();
        assertThat(validadorSesion.sigueVigente(id.toHexString(), AHORA.minusMillis(1))).isFalse();
        assertThat(validadorSesion.sigueVigente(id.toHexString(), AHORA)).isTrue();
        assertThat(validadorSesion.sigueVigente(id.toHexString(), AHORA.plusSeconds(5))).isTrue();
    }
}
