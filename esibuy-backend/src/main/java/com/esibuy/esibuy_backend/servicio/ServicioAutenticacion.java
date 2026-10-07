package com.esibuy.esibuy_backend.servicio;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.esibuy.esibuy_backend.dto.SolicitudLoginDTO;
import com.esibuy.esibuy_backend.excepcion.CodigoError;
import com.esibuy.esibuy_backend.excepcion.CredencialesInvalidasException;
import com.esibuy.esibuy_backend.excepcion.DatosLoginInvalidosException;
import com.esibuy.esibuy_backend.excepcion.ServicioNoDisponibleException;
import com.esibuy.esibuy_backend.modelo.EstadoUsuario;
import com.esibuy.esibuy_backend.modelo.Rol;
import com.esibuy.esibuy_backend.modelo.Usuario;
import com.esibuy.esibuy_backend.repositorio.RepositorioUsuario;
import com.esibuy.esibuy_backend.util.Constantes;

/**
 * Autenticacion por correo y contrasena. Tests: ServicioAutenticacion*Test.
 *
 * Flujo: validacion y normalizacion de las credenciales (un 400 que no cuenta como intento fallido), paso por el
 * limitador (cuenta bloqueada o IP agotada), busqueda por correo exacto y verificacion de la contrasena una sola vez
 * (con un hash ficticio si el correo no existe, para que el trabajo sea el mismo). Solo entra una cuenta ACTIVA con
 * un unico rol valido; cualquier otro caso es la misma, CredencialesInvalidasException, y suma un fallo. Un
 * fallo interno falla cerrado como ServicioNoDisponibleException, sin causa y sin sumar fallo.
 *
 * Aun no es un bean de Spring (sin @Service): se registrara al conectarlo con el controlador en la fase 6, para
 * no alterar el contexto de la aplicacion mientras tanto. La auditoria se conecta en la fase 4.
 */
public class ServicioAutenticacion {

    private static final Logger log = LoggerFactory.getLogger(ServicioAutenticacion.class);

    // Nombres de campo de los errores: coinciden con los del JSON de la solicitud
    private static final String CAMPO_EMAIL = "email";
    private static final String CAMPO_CONTRASENA = "contrasena";

    private final RepositorioUsuario repositorioUsuario;
    private final PasswordEncoder codificadorContrasena;
    private final LimitadorIntentosLogin limitador;
    private final AuditoriaSeguridad auditoria;
    private final Clock reloj;

    //Hash de una contrasena aleatoria, creado con el mismo codificador la primera vez que hace falta. 
    private volatile String hashFicticio;

    public ServicioAutenticacion(RepositorioUsuario repositorioUsuario,
                                 PasswordEncoder codificadorContrasena,
                                 LimitadorIntentosLogin limitador,
                                 AuditoriaSeguridad auditoria,
                                 Clock reloj) {
        this.repositorioUsuario = repositorioUsuario;
        this.codificadorContrasena = codificadorContrasena;
        this.limitador = limitador;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    public ResultadoAutenticacion autenticar(SolicitudLoginDTO solicitud, ContextoPeticion contexto) {
        String email = NormalizadorRegistro.email(solicitud.email());
        String contrasena = NormalizadorRegistro.contrasena(solicitud.contrasena());
        validarCredenciales(email, contrasena);

        limitador.comprobarIntento(email, contexto.ip());

        Comprobacion comprobacion = comprobarContrasena(email, contrasena);
        Optional<ResultadoAutenticacion> resultado = comprobacion.contrasenaCorrecta()
                ? resultadoDeCuentaActiva(comprobacion.usuario())
                : Optional.empty();
        if (resultado.isEmpty()) {
            limitador.registrarFallo(email, contexto.ip());
            throw new CredencialesInvalidasException();
        }
        limitador.registrarExito(email);
        return resultado.get();
    }

    /**
     * Mismas reglas del correo que el registro (obligatorio, hasta 254 caracteres, formato). La contrasena solo
     * debe venir informada y no pasarse de longitud: su fortaleza no se valida al entrar, para no responder con un
     * 400 que distinga una contrasena debil de una incorrecta. Se devuelven todos los errores a la vez.
     */
    private static void validarCredenciales(String email, String contrasena) {
        ErroresRegistro errores = new ErroresRegistro();
        ReglasCamposRegistro.validarEmail(CAMPO_EMAIL, email, errores);
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

    /**
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

    // Solo una cuenta activa con un unico rol valido puede entrar. 
    private static Optional<ResultadoAutenticacion> resultadoDeCuentaActiva(Usuario usuario) {
        if (usuario.getEstado() != EstadoUsuario.ACTIVO) {
            return Optional.empty();
        }
        return rolUnico(usuario).map(rol -> new ResultadoAutenticacion(
                usuario.getId(), usuario.getEmail(), usuario.getPerfil().getNombre(), rol));
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
