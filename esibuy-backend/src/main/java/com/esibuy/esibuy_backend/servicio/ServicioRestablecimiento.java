package com.esibuy.esibuy_backend.servicio;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.esibuy.esibuy_backend.dto.SolicitudRestablecimientoDTO;
import com.esibuy.esibuy_backend.excepcion.CodigoError;
import com.esibuy.esibuy_backend.excepcion.DatosRestablecimientoInvalidosException;
import com.esibuy.esibuy_backend.excepcion.DemasiadosIntentosRestablecimientoException;
import com.esibuy.esibuy_backend.excepcion.ServicioNoDisponibleException;
import com.esibuy.esibuy_backend.modelo.EstadoUsuario;
import com.esibuy.esibuy_backend.modelo.PerfilUsuario;
import com.esibuy.esibuy_backend.modelo.Usuario;
import com.esibuy.esibuy_backend.repositorio.RepositorioUsuario;
import com.esibuy.esibuy_backend.servicio.ResultadoToken.Motivo;

/*
 * Restablecimiento de la contrasena con el token del correo. Tests: ServicioRestablecimientoTest (CP-RST-01 a 17).
 *
 * Orden: limite de intentos de la IP (cada intento cuenta, aunque el token sea basura), validar el token sin consumirlo,
 * cuenta ACTIVA, politica de la contrasena, y solo entonces consumir el token (operacion atomica), codificar, guardar y
 * anular los demas tokens del usuario. Asi una contrasena rechazada no gasta el enlace.
 *
 * Todo enlace que no vale (desconocido, mal formado, caducado, anulado, ya usado, cuenta no activa o inexistente) da el
 * mismo error (campo token, ENLACE_INVALIDO); el motivo solo queda en la auditoria, y reutilizar un enlace gastado es
 * ademas una alerta. La contrasena incumple la politica: error en el campo contrasena con todos los codigos a la vez.
 *
 * La contrasena se normaliza a NFC y no se recorta. Se acepta que sea igual a la actual: compararla convertiria el
 * enlace en un oraculo para probar contrasenas. Se guarda la marca contrasenaCambiadaEn, que invalida las sesiones
 * antiguas (ver FiltroSesionTrasCambioContrasena). Un fallo interno falla cerrado (503) sin detalles y sin escribir en el
 * log mas que el nombre de la clase de la excepcion, porque su mensaje podria contener la contrasena.
 */
@Service
public class ServicioRestablecimiento {

    private static final Logger log = LoggerFactory.getLogger(ServicioRestablecimiento.class);

    private static final String CAMPO_TOKEN = "token";
    private static final String CAMPO_CONTRASENA = "contrasena";

    private final RepositorioUsuario repositorioUsuario;
    private final ServicioTokensRecuperacion tokens;
    private final ValidadorContrasena validadorContrasena;
    private final PasswordEncoder codificador;
    private final LimitadorRestablecimiento limitador;
    private final AuditoriaSeguridad auditoria;
    private final Clock reloj;

    public ServicioRestablecimiento(RepositorioUsuario repositorioUsuario, ServicioTokensRecuperacion tokens,
            ValidadorContrasena validadorContrasena, PasswordEncoder codificador, LimitadorRestablecimiento limitador,
            AuditoriaSeguridad auditoria, Clock reloj) {
        this.repositorioUsuario = repositorioUsuario;
        this.tokens = tokens;
        this.validadorContrasena = validadorContrasena;
        this.codificador = codificador;
        this.limitador = limitador;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    public void restablecer(SolicitudRestablecimientoDTO solicitud, ContextoPeticion contexto) {
        if (!limitador.registrarIntento(contexto.ip())) {
            auditoria.registrarRestablecimientoLimitado(contexto);
            throw new DemasiadosIntentosRestablecimientoException();
        }
        String token = solicitud == null || solicitud.token() == null ? null : solicitud.token().strip();
        String contrasena = Normalizador.contrasena(solicitud == null ? null : solicitud.contrasena());

        String idUsuario = idDelTokenValido(token, contexto);
        Usuario usuario = buscarUsuarioActivo(idUsuario, contexto);
        comprobarPolitica(contrasena, usuario, contexto);
        consumir(token, contexto);
        guardar(usuario, contrasena);
        tokens.invalidarTodos(idUsuario);
        auditoria.registrarRestablecimientoCorrecto(contexto, usuario.getEmail(), idUsuario);
    }

    // Valida el token sin consumirlo. @return el id del usuario; si no vale, lanza el error unico del enlace.
    private String idDelTokenValido(String token, ContextoPeticion contexto) {
        ResultadoToken resultado = token == null || token.isEmpty()
                ? new ResultadoToken(Motivo.DESCONOCIDO, null)
                : protegido(() -> tokens.validar(token));
        if (!resultado.esValido()) {
            rechazarEnlace(resultado.motivo(), contexto);
        }
        return resultado.idUsuario();
    }

    // La cuenta debe existir y estar ACTIVA; si no, es un enlace malo y se anulan los tokens de ese usuario.
    private Usuario buscarUsuarioActivo(String idUsuario, ContextoPeticion contexto) {
        Optional<Usuario> encontrado = protegido(() -> repositorioUsuario.findById(idUsuario));
        if (encontrado.isEmpty() || encontrado.get().getEstado() != EstadoUsuario.ACTIVO) {
            Usuario usuario = encontrado.orElse(null);
            auditoria.registrarRestablecimientoCuentaNoApta(contexto, usuario == null ? null : usuario.getEmail(),
                    usuario == null ? null : usuario.getEstado());
            tokens.invalidarTodos(idUsuario);
            throw errorDelEnlace();
        }
        return encontrado.get();
    }

    // Politica de contrasenas vigente (la misma que el registro), con los datos personales de la cuenta.
    private void comprobarPolitica(String contrasena, Usuario usuario, ContextoPeticion contexto) {
        PerfilUsuario perfil = usuario.getPerfil();
        DatosPersonalesContrasena datos = new DatosPersonalesContrasena(
                perfil == null ? null : perfil.getNombre(), perfil == null ? null : perfil.getApellidos(),
                usuario.getEmail(), perfil == null ? null : perfil.getNombreComercial());
        Set<CodigoError> errores = validadorContrasena.validar(contrasena, datos);
        if (!errores.isEmpty()) {
            auditoria.registrarRestablecimientoContrasenaRechazada(contexto, usuario.getEmail(), usuario.getId(),
                    errores);
            throw new DatosRestablecimientoInvalidosException(Map.of(CAMPO_CONTRASENA, errores));
        }
    }

    // Gasta el token en una operacion atomica: si otra peticion se adelanto, esta pierde la carrera.
    private void consumir(String token, ContextoPeticion contexto) {
        ResultadoToken resultado = protegido(() -> tokens.consumir(token));
        if (!resultado.esValido()) {
            rechazarEnlace(resultado.motivo(), contexto);
        }
    }

    private void guardar(Usuario usuario, String contrasena) {
        protegido(() -> {
            usuario.cambiarContrasena(codificador.encode(contrasena), reloj.instant());
            return repositorioUsuario.save(usuario);
        });
    }

    // Audita el motivo (la reutilizacion de un enlace gastado es una alerta) y lanza el error unico del enlace.
    private void rechazarEnlace(Motivo motivo, ContextoPeticion contexto) {
        if (motivo == Motivo.YA_USADO) {
            auditoria.registrarRestablecimientoTokenReutilizado(contexto);
        } else {
            auditoria.registrarRestablecimientoTokenInvalido(contexto, motivo);
        }
        throw errorDelEnlace();
    }

    private static DatosRestablecimientoInvalidosException errorDelEnlace() {
        return new DatosRestablecimientoInvalidosException(Map.of(CAMPO_TOKEN, Set.of(CodigoError.ENLACE_INVALIDO)));
    }

    // Ejecuta una operacion de la que depende el restablecimiento; si falla, falla cerrado sin detalles.
    private <T> T protegido(java.util.function.Supplier<T> operacion) {
        try {
            return operacion.get();
        } catch (RuntimeException e) {
            log.error("Fallo interno durante el restablecimiento de contrasena: {}", e.getClass().getSimpleName());
            throw new ServicioNoDisponibleException(null);
        }
    }
}
