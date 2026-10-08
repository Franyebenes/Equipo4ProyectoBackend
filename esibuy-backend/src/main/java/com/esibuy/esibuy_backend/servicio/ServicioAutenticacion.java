package com.esibuy.esibuy_backend.servicio;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.esibuy.esibuy_backend.dto.SolicitudLoginDTO;
import com.esibuy.esibuy_backend.excepcion.CodigoError;
import com.esibuy.esibuy_backend.excepcion.CredencialesInvalidasException;
import com.esibuy.esibuy_backend.excepcion.CuentaPendienteActivacionException;
import com.esibuy.esibuy_backend.excepcion.DatosLoginInvalidosException;
import com.esibuy.esibuy_backend.excepcion.LoginBloqueadoTemporalmenteException;
import com.esibuy.esibuy_backend.excepcion.ServicioNoDisponibleException;
import com.esibuy.esibuy_backend.modelo.EstadoUsuario;
import com.esibuy.esibuy_backend.modelo.Rol;
import com.esibuy.esibuy_backend.modelo.Usuario;
import com.esibuy.esibuy_backend.repositorio.RepositorioUsuario;
import com.esibuy.esibuy_backend.util.Constantes;

/*
 * Autenticacion por correo y contrasena.
 *
 * Flujo: validacion y normalizacion de las credenciales (un 400 que no cuenta como intento fallido), paso por el
 * limitador (cuenta bloqueada o IP agotada), busqueda por correo exacto y verificacion de la contrasena una sola vez
 * (con un hash ficticio si el correo no existe, para que el trabajo sea el mismo). Solo entra una cuenta ACTIVA con
 * un unico rol valido; cualquier otro caso es la misma CredencialesInvalidasException y suma un fallo, salvo una
 * cuenta DESACTIVADO con la contrasena correcta, que lanza CuentaPendienteActivacionException sin sumar fallo. Un
 * fallo interno falla cerrado como ServicioNoDisponibleException, sin causa y sin sumar fallo.
 *
 * Cada resultado (correcto, rechazo con su motivo o bloqueo temporal) se comunica a la auditoria.
 */
@Service
public class ServicioAutenticacion {

    private static final Logger log = LoggerFactory.getLogger(ServicioAutenticacion.class);

    // Nombres de campo de los errores: coinciden con los del JSON de la solicitud
    private static final String CAMPO_EMAIL = "email";
    private static final String CAMPO_CONTRASENA = "contrasena";

    private final RepositorioUsuario repositorioUsuario;
    private final PasswordEncoder codificadorContrasena;
    private final LimitadorIntentosLogin limitador;
    private final AuditoriaSeguridad auditoria;

    //Hash de una contrasena aleatoria, creado con el mismo codificador la primera vez que hace falta.
    private volatile String hashFicticio;

    public ServicioAutenticacion(RepositorioUsuario repositorioUsuario,
                                 PasswordEncoder codificadorContrasena,
                                 LimitadorIntentosLogin limitador,
                                 AuditoriaSeguridad auditoria) {
        this.repositorioUsuario = repositorioUsuario;
        this.codificadorContrasena = codificadorContrasena;
        this.limitador = limitador;
        this.auditoria = auditoria;
    }

    /**
     * Calcula el hash ficticio en cuanto arranca la aplicacion, para que la primera peticion con un correo inexistente
     * no tarde mas que las demas. Si falla no pasa nada: se calcula cuando haga falta.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void precalcularHashFicticio() {
        try {
            hashFicticio();
        } catch (RuntimeException e) {
            log.warn("No se pudo precalcular el hash ficticio; se calculara en la primera peticion que lo necesite", e);
        }
    }

    public ResultadoAutenticacion autenticar(SolicitudLoginDTO solicitud, ContextoPeticion contexto) {
        String email = Normalizador.email(solicitud.email());
        String contrasena = Normalizador.contrasena(solicitud.contrasena());
        validarCredenciales(email, contrasena);

        comprobarPasoDelLimitador(email, contexto);

        Comprobacion comprobacion = comprobarContrasena(email, contrasena);
        Usuario usuario = comprobacion.usuario();
        if (usuario == null) {
            throw rechazar(email, contexto, MotivoFalloLogin.CORREO_NO_REGISTRADO);
        }
        if (!comprobacion.contrasenaCorrecta()) {
            throw rechazar(email, contexto, MotivoFalloLogin.CONTRASENA_INCORRECTA);
        }
        // Con la contrasena correcta, una cuenta aun sin activar se lo dice al usuario. Es seguro porque quien no
        // conoce la contrasena no llega aqui. No cuenta como fallo del limitador: la contrasena era buena.
        if (usuario.getEstado() == EstadoUsuario.DESACTIVADO) {
            auditoria.registrarLoginFallido(contexto, email, MotivoFalloLogin.CUENTA_NO_ACTIVA);
            throw new CuentaPendienteActivacionException();
        }
        // Solo una cuenta activa con un unico rol valido puede entrar
        if (usuario.getEstado() != EstadoUsuario.ACTIVO) {
            throw rechazar(email, contexto, MotivoFalloLogin.CUENTA_NO_ACTIVA);
        }
        Optional<Rol> rol = rolUnico(usuario);
        if (rol.isEmpty()) {
            throw rechazar(email, contexto, MotivoFalloLogin.ROLES_INVALIDOS);
        }

        limitador.registrarExito(email);
        auditoria.registrarLoginCorrecto(contexto, email, usuario.getId());
        return new ResultadoAutenticacion(usuario.getId(), usuario.getEmail(), usuario.getPerfil().getNombre(),
                rol.get());
    }

    // Un bloqueo temporal (cuenta o IP) se rechaza tal cual, pero antes queda constancia en la auditoria.
    private void comprobarPasoDelLimitador(String email, ContextoPeticion contexto) {
        try {
            limitador.comprobarIntento(email, contexto.ip());
        } catch (LoginBloqueadoTemporalmenteException e) {
            auditoria.registrarLoginFallido(contexto, email, MotivoFalloLogin.BLOQUEO_TEMPORAL);
            throw e;
        }
    }

    // Todo rechazo de credenciales suma un fallo, queda auditado con su motivo y responde igual para todos.
    private CredencialesInvalidasException rechazar(String email, ContextoPeticion contexto, MotivoFalloLogin motivo) {
        limitador.registrarFallo(email, contexto.ip());
        auditoria.registrarLoginFallido(contexto, email, motivo);
        return new CredencialesInvalidasException();
    }

    /**
     * Mismas reglas del correo que el registro (obligatorio, hasta 254 caracteres, formato). La contrasena solo
     * debe venir informada y no pasarse de longitud: su fortaleza no se valida al entrar, para no responder con un
     * 400 que distinga una contrasena debil de una incorrecta. Se devuelven todos los errores a la vez.
     */
    private static void validarCredenciales(String email, String contrasena) {
        ErroresValidacion errores = new ErroresValidacion();
        ReglasCampos.validarEmail(CAMPO_EMAIL, email, errores);
        if (contrasena == null || contrasena.isBlank()) {
            errores.anadir(CAMPO_CONTRASENA, CodigoError.OBLIGATORIO);
        } else if (contrasena.codePointCount(0, contrasena.length()) > Constantes.LONGITUD_MAXIMA) {
            // Caracteres reales, no unidades UTF-16: un emoji cuenta como 1
            errores.anadir(CAMPO_CONTRASENA, CodigoError.LONGITUD_EXCESIVA);
        }
        if (errores.hayErrores()) {
            throw new DatosLoginInvalidosException(errores.comoMapa());
        }
    }

    /*
     * Busca al usuario y verifica la contrasena exactamente una vez, exista o no el usuario. Cualquier fallo de la
     * BBDD o del codificador falla cerrado: sin causa ni detalles en la excepcion, que queda solo en el log.
     */
    private Comprobacion comprobarContrasena(String email, String contrasena) {
        try {
            Usuario usuario = repositorioUsuario.buscarPorEmail(email).orElse(null);
            String hash = usuario != null ? usuario.getPasswordHash() : hashFicticio();
            return new Comprobacion(usuario, codificadorContrasena.matches(contrasena, hash));
        } catch (RuntimeException e) {
            log.error("Fallo interno durante la autenticacion", e);
            throw new ServicioNoDisponibleException(null);
        }
    }

    private String hashFicticio() {
        String hash = hashFicticio;
        if (hash == null) {
            hash = codificadorContrasena.encode(UUID.randomUUID().toString());
            hashFicticio = hash;
        }
        return hash;
    }

    //El rol del usuario; si el documento tiene cero roles, mas de uno o uno desconocido, falla cerrado y lo registra.
    private static Optional<Rol> rolUnico(Usuario usuario) {
        List<Rol> roles = usuario.getRoles();
        if (roles == null || roles.size() != 1 || roles.get(0) == null) {
            log.error("Usuario {} con roles invalidos: no puede iniciar sesion", usuario.getId());
            return Optional.empty();
        }
        return Optional.of(roles.get(0));
    }

    private record Comprobacion(Usuario usuario, boolean contrasenaCorrecta) {
    }
}
