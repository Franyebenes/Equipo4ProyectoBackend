package com.esibuy.esibuy_backend.servicio;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;

import org.bson.types.ObjectId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import com.esibuy.esibuy_backend.modelo.EstadoToken;
import com.esibuy.esibuy_backend.modelo.TokenRecuperacion;
import com.esibuy.esibuy_backend.servicio.ResultadoToken.Motivo;

/*
 * Emision, validacion y consumo de los tokens de recuperacion de contrasena. Tests:
 * TokensRecuperacionIntegracionMongoTest (CP-TOK-10 a CP-TOK-37).
 *
 * <ul>
 *   <li>Solo se guarda el hash del token (nunca el valor en claro).
 *   <li>Un token vale mientras ahora &lt; caducaEn.
 *   <li>Solo hay un token vigente por usuario: emitir uno nuevo anula el anterior (y un indice unico parcial de la BBDD
 *       lo garantiza aunque dos solicitudes coincidan).
 *   <li>El consumo es una unica operacion atomica de la base de datos: con peticiones simultaneas solo una gana.
 *   <li>Un valor que no tiene forma de token se descarta sin consultar la base de datos.
 * </ul>
 */
@Service
public class ServicioTokensRecuperacion {

    // Tiempo de vida de un token desde que se emite. 
    public static final Duration VALIDEZ = Duration.ofMinutes(15);

    // 43 caracteres base64url: 256 bits sin relleno. Cualquier otra cosa no es un token. 
    private static final Pattern FORMATO_TOKEN = Pattern.compile("^[A-Za-z0-9_-]{43}$");

    private final MongoTemplate mongo;
    private final GeneradorTokens generador;
    private final Clock reloj;

    public ServicioTokensRecuperacion(MongoTemplate mongo, GeneradorTokens generador, Clock reloj) {
        this.mongo = mongo;
        this.generador = generador;
        this.reloj = reloj;
    }

    // Emite un token para el usuario y anula los que siguieran vigentes.
    public TokenEmitido emitir(String idUsuario) {
        exigirObjectId(idUsuario);
        Instant ahora = reloj.instant();
        String token = generador.nuevoToken();
        TokenRecuperacion nuevo = new TokenRecuperacion(idUsuario, GeneradorTokens.hash(token), ahora,
                ahora.plus(VALIDEZ));

        invalidarTodos(idUsuario);
        try {
            mongo.insert(nuevo);
        } catch (DuplicateKeyException coincidencia) {
            // Otra solicitud del mismo usuario se colo entre medias: se anula tambien y se reintenta una vez
            invalidarTodos(idUsuario);
            mongo.insert(nuevo);
        }
        return new TokenEmitido(token, ahora, nuevo.getCaducaEn());
    }

    // Comprueba el token sin modificar nada.
    public ResultadoToken validar(String token) {
        if (!tieneFormatoDeToken(token)) {
            return rechazo(Motivo.DESCONOCIDO);
        }
        TokenRecuperacion guardado = mongo.findOne(query(where("hashToken").is(GeneradorTokens.hash(token))),
                TokenRecuperacion.class);
        return evaluar(guardado, reloj.instant());
    }

    // Valida y marca como usado en una sola operacion atomica: solo una llamada concurrente puede ganar.
    public ResultadoToken consumir(String token) {
        if (!tieneFormatoDeToken(token)) {
            return rechazo(Motivo.DESCONOCIDO);
        }
        Instant ahora = reloj.instant();
        String hash = GeneradorTokens.hash(token);
        TokenRecuperacion consumido = mongo.findAndModify(
                query(where("hashToken").is(hash).and("estado").is(EstadoToken.VIGENTE.name())
                        .and("caducaEn").gt(ahora)),
                new Update().set("estado", EstadoToken.USADO.name()).set("usadoEn", ahora),
                FindAndModifyOptions.options().returnNew(true), TokenRecuperacion.class);
        if (consumido != null) {
            return new ResultadoToken(Motivo.VALIDO, consumido.getIdUsuario());
        }

        // No se pudo consumir: se averigua por que, solo para el motivo (que va a la auditoria)
        ResultadoToken motivo = evaluar(
                mongo.findOne(query(where("hashToken").is(hash)), TokenRecuperacion.class), ahora);
        return motivo.esValido() ? rechazo(Motivo.DESCONOCIDO) : motivo;
    }

    // Anula todos los tokens vigentes del usuario. @return cuantos anulo
    public long invalidarTodos(String idUsuario) {
        exigirObjectId(idUsuario);
        return mongo.updateMulti(
                query(where("idUsuario").is(new ObjectId(idUsuario)).and("estado").is(EstadoToken.VIGENTE.name())),
                new Update().set("estado", EstadoToken.INVALIDADO.name()), TokenRecuperacion.class).getModifiedCount();
    }

    // El estado USADO prevalece sobre CADUCADO: reutilizar un enlace ya gastado es una alerta de seguridad.
    private static ResultadoToken evaluar(TokenRecuperacion guardado, Instant ahora) {
        if (guardado == null) {
            return rechazo(Motivo.DESCONOCIDO);
        }
        return switch (guardado.getEstado()) {
            case USADO -> rechazo(Motivo.YA_USADO);
            case INVALIDADO -> rechazo(Motivo.INVALIDADO);
            case VIGENTE -> ahora.isBefore(guardado.getCaducaEn())
                    ? new ResultadoToken(Motivo.VALIDO, guardado.getIdUsuario())
                    : rechazo(Motivo.CADUCADO);
        };
    }

    private static ResultadoToken rechazo(Motivo motivo) {
        return new ResultadoToken(motivo, null);
    }

    private static boolean tieneFormatoDeToken(String token) {
        return token != null && FORMATO_TOKEN.matcher(token).matches();
    }

    private static void exigirObjectId(String idUsuario) {
        if (idUsuario == null || !ObjectId.isValid(idUsuario)) {
            throw new IllegalArgumentException("El id de usuario no es valido");
        }
    }
}
