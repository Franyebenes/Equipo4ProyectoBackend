package com.esibuy.esibuy_backend.repositorio;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashSet;
import java.util.List;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;

import com.esibuy.esibuy_backend.modelo.DosFactores;
import com.esibuy.esibuy_backend.modelo.Usuario;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;

/*
 * Cambios del segundo factor de un usuario. Cada uno es una unica operacion atomica de la base de datos, con su
 * condicion dentro de la consulta: asi un codigo TOTP o de recuperacion que solo vale una vez no se puede colar dos veces
 * con peticiones simultaneas, y un secreto pendiente solo se activa si sigue siendo el que se verifico. Solo tocan el
 * subdocumento dosFactores: el resto del usuario queda igual. Devuelven true si la operacion se aplico y false si la
 * condicion no se cumplia (usuario inexistente, estado incorrecto, ya usado...). Los datos mal formados se rechazan con
 * IllegalArgumentException sin llegar a la base de datos.
 *
 * Se usa el driver directamente y no las consultas de Spring Data para que lo que se envia a MongoDB sea exactamente lo
 * que se lee aqui, sin conversiones por el camino. La lectura del estado es la de RepositorioUsuario (Usuario.getDosFactores).
 * Tests: RepositorioDosFactoresIntegracionMongoTest (CP-2FA-127 a CP-2FA-166).
 */
@Repository
public class RepositorioDosFactores {

    private static final String DOS_FACTORES = "dosFactores";
    private static final String ACTIVO = DOS_FACTORES + ".activo";
    private static final String SECRETO_CIFRADO = DOS_FACTORES + ".secretoCifrado";
    private static final String PENDIENTE = DOS_FACTORES + ".pendiente";
    private static final String PENDIENTE_SECRETO = PENDIENTE + ".secretoCifrado";
    private static final String PENDIENTE_CREADO_EN = PENDIENTE + ".creadoEn";
    private static final String ULTIMO_PASO = DOS_FACTORES + ".ultimoPaso";
    private static final String ACTIVADO_EN = DOS_FACTORES + ".activadoEn";
    private static final String CODIGOS = DOS_FACTORES + ".codigosRecuperacion";
    private static final String CODIGO_USADO = CODIGOS + ".$.usado";
    private static final String CODIGO_USADO_EN = CODIGOS + ".$.usadoEn";

    private final MongoTemplate mongo;

    public RepositorioDosFactores(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    /*
     * Guarda el secreto de una activacion que aun no se ha confirmado. Solo si la cuenta no tiene el 2FA activo; si ya habia
     * un secreto pendiente, lo sustituye (la persona reinicia la activacion).
     */
    public boolean guardarActivacionPendiente(String idUsuario, String secretoCifrado, Instant creadoEn) {
        ObjectId id = exigirObjectId(idUsuario);
        DosFactores.exigirSecretoCifrado(secretoCifrado);
        exigirNoNulo(creadoEn, "creadoEn");

        Bson condicion = Filters.and(Filters.eq("_id", id), Filters.ne(ACTIVO, true));
        Bson cambio = Updates.combine(
                Updates.set(ACTIVO, false),
                Updates.set(PENDIENTE, new Document("secretoCifrado", secretoCifrado).append("creadoEn", Date.from(creadoEn))));
        return aplicar(condicion, cambio);
    }

    /*
     * Activa el 2FA con el secreto pendiente que se ha verificado. La condicion exige que sea exactamente ese secreto (si la
     * persona reinicio la activacion entre medias, no se activa) y que no haya caducado: vale mientras ahora sea anterior a
     * creadoEn + caducidadPendiente. Deja el secreto como definitivo, anota el paso del codigo de confirmacion para que no
     * se pueda reutilizar y guarda los hashes de los codigos de recuperacion.
     */
    public boolean activar(String idUsuario, String secretoCifradoVerificado, long paso, List<String> hashesCodigos,
                           Instant ahora, Duration caducidadPendiente) {
        ObjectId id = exigirObjectId(idUsuario);
        DosFactores.exigirSecretoCifrado(secretoCifradoVerificado);
        exigirPaso(paso);
        List<Document> codigos = codigosNuevos(hashesCodigos);
        exigirNoNulo(ahora, "ahora");
        if (caducidadPendiente == null || caducidadPendiente.isZero() || caducidadPendiente.isNegative()) {
            throw new IllegalArgumentException("La caducidad debe ser una duracion positiva");
        }

        Bson condicion = Filters.and(
                Filters.eq("_id", id),
                Filters.eq(ACTIVO, false),
                Filters.eq(PENDIENTE_SECRETO, secretoCifradoVerificado),
                Filters.gt(PENDIENTE_CREADO_EN, Date.from(ahora.minus(caducidadPendiente))));
        Bson cambio = Updates.combine(
                Updates.set(ACTIVO, true),
                Updates.set(SECRETO_CIFRADO, secretoCifradoVerificado),
                Updates.set(ACTIVADO_EN, Date.from(ahora)),
                Updates.set(ULTIMO_PASO, paso),
                Updates.set(CODIGOS, codigos),
                Updates.unset(PENDIENTE));
        return aplicar(condicion, cambio);
    }

    /*
     * Anota el paso TOTP del codigo aceptado. Solo si el 2FA esta activo y el paso es posterior al ultimo aceptado: de dos
     * peticiones simultaneas con el mismo codigo solo una lo consigue, y un paso viejo no puede pisar a uno nuevo.
     */
    public boolean registrarPasoAceptado(String idUsuario, long paso) {
        ObjectId id = exigirObjectId(idUsuario);
        exigirPaso(paso);

        Bson condicion = Filters.and(
                Filters.eq("_id", id),
                Filters.eq(ACTIVO, true),
                Filters.or(Filters.exists(ULTIMO_PASO, false), Filters.lt(ULTIMO_PASO, paso)));
        return aplicar(condicion, Updates.set(ULTIMO_PASO, paso));
    }

    // Marca como usado un codigo de recuperacion que seguia sin usar. Solo con el 2FA activo; una sola vez por codigo. 
    public boolean consumirCodigoRecuperacion(String idUsuario, String hash, Instant ahora) {
        ObjectId id = exigirObjectId(idUsuario);
        DosFactores.exigirHash(hash);
        exigirNoNulo(ahora, "ahora");

        Bson condicion = Filters.and(
                Filters.eq("_id", id),
                Filters.eq(ACTIVO, true),
                Filters.elemMatch(CODIGOS, Filters.and(Filters.eq("hash", hash), Filters.eq("usado", false))));
        // El operador posicional ($) apunta al elemento que ha cumplido la condicion
        Bson cambio = Updates.combine(Updates.set(CODIGO_USADO, true), Updates.set(CODIGO_USADO_EN, Date.from(ahora)));
        return aplicar(condicion, cambio);
    }

    // Sustituye todos los codigos de recuperacion (los anteriores dejan de valer). Solo con el 2FA activo.
    public boolean reemplazarCodigosRecuperacion(String idUsuario, List<String> hashesCodigos) {
        ObjectId id = exigirObjectId(idUsuario);
        List<Document> codigos = codigosNuevos(hashesCodigos);

        return aplicar(Filters.and(Filters.eq("_id", id), Filters.eq(ACTIVO, true)), Updates.set(CODIGOS, codigos));
    }

    /*
     * Anota la marca de cambio de las credenciales (contrasenaCambiadaEn, la misma que usa el restablecimiento de contrasena):
     * las sesiones iniciadas antes de ese instante dejan de valer. Solo toca ese campo. False si el usuario no existe.
     */
    public boolean marcarCredencialesCambiadas(String idUsuario, Instant instante) {
        ObjectId id = exigirObjectId(idUsuario);
        exigirNoNulo(instante, "instante");

        return aplicar(Filters.eq("_id", id), Updates.set("contrasenaCambiadaEn", Date.from(instante)));
    }

    /* auxiliares*/

    private boolean aplicar(Bson condicion, Bson cambio) {
        return usuarios().updateOne(condicion, cambio).getMatchedCount() > 0;
    }

    private MongoCollection<Document> usuarios() {
        return mongo.getCollection(mongo.getCollectionName(Usuario.class));
    }

    private static ObjectId exigirObjectId(String id) {
        // ObjectId.isValid solo admite 24 caracteres hexadecimales: un texto con operadores no llega a la consulta
        if (id == null || !ObjectId.isValid(id)) {
            throw new IllegalArgumentException("El identificador de usuario no es valido");
        }
        return new ObjectId(id);
    }

    private static void exigirPaso(long paso) {
        if (paso < 0) {
            throw new IllegalArgumentException("El paso no puede ser negativo");
        }
    }

    private static void exigirNoNulo(Object valor, String nombre) {
        if (valor == null) {
            throw new IllegalArgumentException(nombre + " es obligatorio");
        }
    }

    // Los documentos de los codigos nuevos (todos sin usar). Falla si la lista esta vacia, es demasiado larga, repite un
    // hash o alguno no tiene forma de hash: un codigo en claro no puede llegar a guardarse.
    private static List<Document> codigosNuevos(List<String> hashes) {
        if (hashes == null || hashes.isEmpty() || hashes.size() > DosFactores.MAXIMO_CODIGOS) {
            throw new IllegalArgumentException(
                    "Los codigos de recuperacion deben ser entre 1 y " + DosFactores.MAXIMO_CODIGOS);
        }
        if (new HashSet<>(hashes).size() != hashes.size()) {
            throw new IllegalArgumentException("Los codigos de recuperacion no pueden repetirse");
        }
        return hashes.stream()
                .map(hash -> new Document("hash", DosFactores.exigirHash(hash)).append("usado", false))
                .toList();
    }
}
