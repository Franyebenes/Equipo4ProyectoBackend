package com.esibuy.esibuy_backend.servicio;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.esibuy.esibuy_backend.dto.EstadoDosFactoresDTO;
import com.esibuy.esibuy_backend.dto.RespuestaActivacionDosFactoresDTO;
import com.esibuy.esibuy_backend.dto.RespuestaCodigosRecuperacionDTO;
import com.esibuy.esibuy_backend.excepcion.CodigoError;
import com.esibuy.esibuy_backend.excepcion.DatosDosFactoresInvalidosException;
import com.esibuy.esibuy_backend.excepcion.DemasiadosIntentosDosFactoresException;
import com.esibuy.esibuy_backend.excepcion.OperacionNoPermitidaException;
import com.esibuy.esibuy_backend.excepcion.SegundoFactorNoVigenteException;
import com.esibuy.esibuy_backend.excepcion.ServicioNoDisponibleException;
import com.esibuy.esibuy_backend.excepcion.UsuarioNoEncontradoException;
import com.esibuy.esibuy_backend.modelo.DosFactores;
import com.esibuy.esibuy_backend.modelo.EstadoUsuario;
import com.esibuy.esibuy_backend.modelo.Usuario;
import com.esibuy.esibuy_backend.repositorio.RepositorioDosFactores;
import com.esibuy.esibuy_backend.repositorio.RepositorioUsuario;

/*
 * Activar, consultar, desactivar y regenerar los codigos de recuperacion del segundo factor (TOTP). La cuenta es siempre
 * la de la sesion (el controlador pasa su id). Tests: ServicioDosFactoresTest (CP-2FA-179 a CP-2FA-225).
 *
 * Operar con el 2FA exige repetir la contrasena. Desactivar y regenerar los codigos exigen ademas un codigo valido: el TOTP
 * del movil o uno de recuperacion (que se consume). Orden: formato (un campo vacio es un 400 que no cuenta como fallo),
 * limite de fallos, usuario, estado del 2FA, contrasena y, solo entonces, el codigo: una contrasena mal puesta no llega a
 * probar el codigo. Los fallos se suman al limitador y quedan en la auditoria; un TOTP ya aceptado no vale otra vez
 * (anti-reutilizacion atomica en la base de datos) y cuenta como fallo.
 *
 * Al activar, desactivar o regenerar se marcan las credenciales como cambiadas un milisegundo antes del instante del cambio:
 * las demas sesiones dejan de valer y la que el controlador renueva justo despues sobrevive (una sesion vale solo si empezo
 * estrictamente despues de la marca). Cada cambio avisa por correo, en segundo plano, y se audita. El secreto pendiente
 * caduca a los 10 minutos exactos.
 *
 * Un fallo interno falla cerrado (503) sin detalles y sin escribir en el log mas que el nombre de la clase de la excepcion,
 * porque su mensaje podria contener datos sensibles. Nunca se escribe en un log ni en un error la contrasena, el codigo, el
 * secreto ni los codigos de recuperacion.
 */
@Service
public class ServicioDosFactores {

    private static final Logger log = LoggerFactory.getLogger(ServicioDosFactores.class);

    private static final String CAMPO_CONTRASENA = "contrasena";
    private static final String CAMPO_CODIGO = "codigo";

    private final RepositorioUsuario repositorioUsuario;
    private final RepositorioDosFactores repositorioDosFactores;
    private final CifradorSecretos cifrador;
    private final GeneradorTotp totp;
    private final GeneradorCodigosRecuperacion generadorCodigos;
    private final PasswordEncoder codificador;
    private final LimitadorDosFactores limitador;
    private final NotificadorCorreo notificador;
    private final PlantillasCorreo plantillas;
    private final AuditoriaSeguridad auditoria;
    private final PropiedadesDosFactores propiedades;
    private final Clock reloj;

    @SuppressWarnings("java:S107") // Un colaborador por responsabilidad: es un servicio de orquestacion
    public ServicioDosFactores(RepositorioUsuario repositorioUsuario, RepositorioDosFactores repositorioDosFactores,
            CifradorSecretos cifrador, GeneradorTotp totp, GeneradorCodigosRecuperacion generadorCodigos,
            PasswordEncoder codificador, LimitadorDosFactores limitador, NotificadorCorreo notificador,
            PlantillasCorreo plantillas, AuditoriaSeguridad auditoria, PropiedadesDosFactores propiedades, Clock reloj) {
        this.repositorioUsuario = repositorioUsuario;
        this.repositorioDosFactores = repositorioDosFactores;
        this.cifrador = cifrador;
        this.totp = totp;
        this.generadorCodigos = generadorCodigos;
        this.codificador = codificador;
        this.limitador = limitador;
        this.notificador = notificador;
        this.plantillas = plantillas;
        this.auditoria = auditoria;
        this.propiedades = propiedades;
        this.reloj = reloj;
    }

    public EstadoDosFactoresDTO estado(String idUsuario) {
        Usuario usuario = cargar(idUsuario);
        DosFactores dosFactores = usuario.getDosFactores();
        return new EstadoDosFactoresDTO(usuario.tieneDosFactoresActivo(),
                dosFactores == null ? 0 : dosFactores.codigosDisponibles());
    }

    /** Genera el secreto, lo guarda cifrado como pendiente y devuelve lo necesario para dibujar el QR. */
    public RespuestaActivacionDosFactoresDTO iniciarActivacion(String idUsuario, String contrasena,
            ContextoPeticion contexto) {
        String clave = Normalizador.contrasena(contrasena);
        exigirContrasena(clave);
        comprobarLimite(idUsuario, contexto);
        Usuario usuario = cargar(idUsuario);
        exigirInactivo(usuario);
        comprobarContrasena(usuario, clave, contexto);

        Instant ahora = reloj.instant();
        byte[] secreto = totp.nuevoSecreto();
        String cifrado = protegido(() -> cifrador.cifrar(secreto, idUsuario));
        if (!protegido(() -> repositorioDosFactores.guardarActivacionPendiente(idUsuario, cifrado, ahora))) {
            throw new OperacionNoPermitidaException();
        }
        auditoria.registrarDosFactoresActivacionIniciada(contexto, usuario.getEmail(), idUsuario);
        return new RespuestaActivacionDosFactoresDTO(
                UriOtpauth.construir(propiedades.emisor(), usuario.getEmail(), secreto), Base32.codificar(secreto));
    }

    /** Activa el 2FA si el codigo corresponde al secreto pendiente. Devuelve los codigos de recuperacion, una sola vez. */
    public RespuestaCodigosRecuperacionDTO confirmarActivacion(String idUsuario, String codigo,
            ContextoPeticion contexto) {
        exigirCodigo(codigo);
        comprobarLimite(idUsuario, contexto);
        Usuario usuario = cargar(idUsuario);
        exigirInactivo(usuario);

        Instant ahora = reloj.instant();
        DosFactores.Pendiente pendiente = usuario.getDosFactores() == null ? null : usuario.getDosFactores().getPendiente();
        if (pendiente == null || pendiente.haCaducado(ahora, propiedades.caducidadActivacion())) {
            auditoria.registrarDosFactoresFallido(contexto, usuario.getEmail(), idUsuario,
                    MotivoFalloDosFactores.ACTIVACION_NO_VIGENTE);
            throw errores(CAMPO_CODIGO, CodigoError.ACTIVACION_NO_VIGENTE);
        }
        byte[] secreto = protegido(() -> cifrador.descifrar(pendiente.getSecretoCifrado(), idUsuario));
        OptionalLong paso = totp.verificar(secreto, codigo, GeneradorTotp.NINGUN_PASO);
        if (paso.isEmpty()) {
            throw rechazoDelCodigo(usuario, contexto, MotivoFalloDosFactores.CODIGO_INCORRECTO);
        }

        List<String> codigos = generadorCodigos.nuevosCodigos(propiedades.codigosRecuperacion());
        List<String> hashes = codigos.stream().map(generadorCodigos::hash).toList();
        // Con el secreto pendiente EXACTO que se ha verificado: si se reinicio la activacion entre medias, no se activa
        boolean activado = protegido(() -> repositorioDosFactores.activar(idUsuario, pendiente.getSecretoCifrado(),
                paso.getAsLong(), hashes, ahora, propiedades.caducidadActivacion()));
        if (!activado) {
            throw new OperacionNoPermitidaException();
        }
        cambioDeSeguridad(usuario, ahora);
        auditoria.registrarDosFactoresActivado(contexto, usuario.getEmail(), idUsuario);
        avisar(() -> plantillas.dosFactoresActivado(usuario.getEmail()));
        return respuestaCon(codigos);
    }

    /** Quita el 2FA. Exige la contrasena y un codigo valido (TOTP o de recuperacion). */
    public void desactivar(String idUsuario, String contrasena, String codigo, ContextoPeticion contexto) {
        String clave = Normalizador.contrasena(contrasena);
        exigirContrasenaYCodigo(clave, codigo);
        comprobarLimite(idUsuario, contexto);
        Usuario usuario = cargar(idUsuario);
        exigirActivo(usuario);
        comprobarContrasena(usuario, clave, contexto);
        Instant ahora = reloj.instant();
        comprobarCodigo(usuario, codigo, ahora, contexto);

        if (!protegido(() -> repositorioDosFactores.desactivar(idUsuario))) {
            throw new OperacionNoPermitidaException();
        }
        cambioDeSeguridad(usuario, ahora);
        auditoria.registrarDosFactoresDesactivado(contexto, usuario.getEmail(), idUsuario);
        avisar(() -> plantillas.dosFactoresDesactivado(usuario.getEmail()));
    }

    /** Sustituye los codigos de recuperacion por otros nuevos. Exige la contrasena y un codigo valido. */
    public RespuestaCodigosRecuperacionDTO regenerarCodigos(String idUsuario, String contrasena, String codigo,
            ContextoPeticion contexto) {
        String clave = Normalizador.contrasena(contrasena);
        exigirContrasenaYCodigo(clave, codigo);
        comprobarLimite(idUsuario, contexto);
        Usuario usuario = cargar(idUsuario);
        exigirActivo(usuario);
        comprobarContrasena(usuario, clave, contexto);
        Instant ahora = reloj.instant();
        comprobarCodigo(usuario, codigo, ahora, contexto);

        List<String> codigos = generadorCodigos.nuevosCodigos(propiedades.codigosRecuperacion());
        List<String> hashes = codigos.stream().map(generadorCodigos::hash).toList();
        if (!protegido(() -> repositorioDosFactores.reemplazarCodigosRecuperacion(idUsuario, hashes))) {
            throw new OperacionNoPermitidaException();
        }
        cambioDeSeguridad(usuario, ahora);
        auditoria.registrarDosFactoresCodigosRegenerados(contexto, usuario.getEmail(), idUsuario);
        avisar(() -> plantillas.codigosRecuperacionRegenerados(usuario.getEmail()));
        return respuestaCon(codigos);
    }

    /*
     * Verifica el codigo del segundo paso del login (el desafio lo gestiona el controlador). Es la misma comprobacion que
     * desactivar y regenerar: un TOTP se acepta una sola vez por paso (compartido con esas operaciones) y un codigo de
     * recuperacion se consume. No pide la contrasena (ya se comprobo en el primer paso) ni marca las credenciales como
     * cambiadas ni avisa por correo: entrar no cambia nada de la cuenta. Si entre los dos pasos la cuenta dejo de poder entrar
     * (borrada, bloqueada, desactivada) o ya no tiene el 2FA activo, el desafio no es vigente. Tests: ServicioDosFactoresLoginTest.
     */
    public void verificarParaLogin(String idUsuario, String codigo, ContextoPeticion contexto) {
        exigirCodigo(codigo);
        comprobarLimite(idUsuario, contexto);
        Usuario usuario = protegido(() -> repositorioUsuario.findById(idUsuario)).orElse(null);
        if (usuario == null || usuario.getEstado() != EstadoUsuario.ACTIVO || !usuario.tieneDosFactoresActivo()) {
            auditoria.registrarLoginSegundoFactorNoVigente(contexto);
            throw new SegundoFactorNoVigenteException();
        }
        comprobarCodigo(usuario, codigo, reloj.instant(), contexto);
        limitador.registrarExito(idUsuario);
        auditoria.registrarLoginCorrecto(contexto, usuario.getEmail(), idUsuario);
    }

    // ------------------------------------------------------------------ auxiliares

    // Un campo vacio es un 400 con todos los campos que faltan; no cuenta como fallo del limitador.
    private static void exigirContrasena(String contrasena) {
        exigirCampos(faltaTexto(contrasena), false);
    }

    private static void exigirCodigo(String codigo) {
        exigirCampos(false, faltaTexto(codigo));
    }

    private static void exigirContrasenaYCodigo(String contrasena, String codigo) {
        exigirCampos(faltaTexto(contrasena), faltaTexto(codigo));
    }

    private static boolean faltaTexto(String valor) {
        return valor == null || valor.isBlank();
    }

    private static void exigirCampos(boolean faltaContrasena, boolean faltaCodigo) {
        Map<String, Set<CodigoError>> errores = new HashMap<>();
        if (faltaContrasena) {
            errores.put(CAMPO_CONTRASENA, Set.of(CodigoError.OBLIGATORIO));
        }
        if (faltaCodigo) {
            errores.put(CAMPO_CODIGO, Set.of(CodigoError.OBLIGATORIO));
        }
        if (!errores.isEmpty()) {
            throw new DatosDosFactoresInvalidosException(errores);
        }
    }

    private void comprobarLimite(String idUsuario, ContextoPeticion contexto) {
        if (!limitador.permitido(idUsuario, contexto.ip())) {
            auditoria.registrarDosFactoresLimitado(contexto, idUsuario);
            throw new DemasiadosIntentosDosFactoresException();
        }
    }

    private Usuario cargar(String idUsuario) {
        return protegido(() -> repositorioUsuario.findById(idUsuario)).orElseThrow(UsuarioNoEncontradoException::new);
    }

    private static void exigirInactivo(Usuario usuario) {
        if (usuario.tieneDosFactoresActivo()) {
            throw new OperacionNoPermitidaException();
        }
    }

    private static void exigirActivo(Usuario usuario) {
        if (!usuario.tieneDosFactoresActivo()) {
            throw new OperacionNoPermitidaException();
        }
    }

    private void comprobarContrasena(Usuario usuario, String contrasena, ContextoPeticion contexto) {
        if (!protegido(() -> codificador.matches(contrasena, usuario.getPasswordHash()))) {
            limitador.registrarFallo(usuario.getId(), contexto.ip());
            auditoria.registrarDosFactoresFallido(contexto, usuario.getEmail(), usuario.getId(),
                    MotivoFalloDosFactores.CONTRASENA_INCORRECTA);
            throw errores(CAMPO_CONTRASENA, CodigoError.CONTRASENA_INCORRECTA);
        }
    }

    /*
     * Un codigo de recuperacion (16 caracteres) se consume; si no, es un TOTP y se acepta una sola vez por paso. Los dos
     * casos son operaciones atomicas de la base de datos: con peticiones simultaneas solo una gana.
     */
    private void comprobarCodigo(Usuario usuario, String codigo, Instant ahora, ContextoPeticion contexto) {
        String id = usuario.getId();
        DosFactores dosFactores = usuario.getDosFactores();
        String recuperacion = GeneradorCodigosRecuperacion.normalizar(codigo);
        if (recuperacion != null) {
            String hash = generadorCodigos.hash(recuperacion);
            if (!protegido(() -> repositorioDosFactores.consumirCodigoRecuperacion(id, hash, ahora))) {
                throw rechazoDelCodigo(usuario, contexto, MotivoFalloDosFactores.CODIGO_INCORRECTO);
            }
            auditoria.registrarDosFactoresCodigoRecuperacionUsado(contexto, usuario.getEmail(), id,
                    dosFactores.codigosDisponibles() - 1);
            return;
        }
        byte[] secreto = protegido(() -> cifrador.descifrar(dosFactores.getSecretoCifrado(), id));
        OptionalLong paso = totp.verificar(secreto, codigo, dosFactores.getUltimoPaso());
        if (paso.isEmpty()) {
            throw rechazoDelCodigo(usuario, contexto, MotivoFalloDosFactores.CODIGO_INCORRECTO);
        }
        if (!protegido(() -> repositorioDosFactores.registrarPasoAceptado(id, paso.getAsLong()))) {
            // El codigo es correcto pero otra peticion ya lo gasto: al cliente le llega como incorrecto
            throw rechazoDelCodigo(usuario, contexto, MotivoFalloDosFactores.CODIGO_REUTILIZADO);
        }
    }

    private DatosDosFactoresInvalidosException rechazoDelCodigo(Usuario usuario, ContextoPeticion contexto,
            MotivoFalloDosFactores motivo) {
        limitador.registrarFallo(usuario.getId(), contexto.ip());
        auditoria.registrarDosFactoresFallido(contexto, usuario.getEmail(), usuario.getId(), motivo);
        return errores(CAMPO_CODIGO, CodigoError.CODIGO_INCORRECTO);
    }

    // Tras un cambio con exito: la marca (un milisegundo antes) invalida las demas sesiones y los fallos de la cuenta se olvidan.
    private void cambioDeSeguridad(Usuario usuario, Instant ahora) {
        protegido(() -> repositorioDosFactores.marcarCredencialesCambiadas(usuario.getId(), ahora.minusMillis(1)));
        limitador.registrarExito(usuario.getId());
    }

    // El aviso no puede hacer fallar una operacion que ya se ha aplicado.
    private void avisar(Supplier<MensajeCorreo> mensaje) {
        try {
            notificador.notificar(mensaje.get());
        } catch (RuntimeException e) {
            log.warn("No se pudo preparar el aviso por correo del segundo factor: {}", e.getClass().getSimpleName());
        }
    }

    private static RespuestaCodigosRecuperacionDTO respuestaCon(List<String> codigos) {
        return new RespuestaCodigosRecuperacionDTO(codigos.stream().map(GeneradorCodigosRecuperacion::formatear).toList());
    }

    private static DatosDosFactoresInvalidosException errores(String campo, CodigoError codigo) {
        return new DatosDosFactoresInvalidosException(Map.of(campo, Set.of(codigo)));
    }

    // Ejecuta una operacion de la que depende el servicio; si falla, falla cerrado sin detalles.
    private <T> T protegido(Supplier<T> operacion) {
        try {
            return operacion.get();
        } catch (RuntimeException e) {
            log.error("Fallo interno en una operacion del segundo factor: {}", e.getClass().getSimpleName());
            throw new ServicioNoDisponibleException(null);
        }
    }
}
