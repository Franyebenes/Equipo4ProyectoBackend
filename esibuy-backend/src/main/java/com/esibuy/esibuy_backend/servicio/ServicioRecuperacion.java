package com.esibuy.esibuy_backend.servicio;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.esibuy.esibuy_backend.dto.SolicitudRecuperacionDTO;
import com.esibuy.esibuy_backend.excepcion.DatosRecuperacionInvalidosException;
import com.esibuy.esibuy_backend.excepcion.ServicioNoDisponibleException;
import com.esibuy.esibuy_backend.modelo.EstadoUsuario;
import com.esibuy.esibuy_backend.modelo.Usuario;
import com.esibuy.esibuy_backend.repositorio.RepositorioUsuario;

/*
 * Solicitud de recuperacion de contrasena. Tests: ServicioRecuperacionTest (CP-REC-01 a CP-REC-13) y
 *
 * <p>Segun el estado de la cuenta: ACTIVO recibe un enlace con un token de un solo uso; DESACTIVADO (pendiente de
 * activacion) recibe un aviso sin enlace; BLOQUEADO, cualquier otro estado y los correos no registrados no reciben
 * nada. Hacia fuera el metodo es igual en todos los casos (devuelve sin mas), y lo que depende de la cuenta (emitir
 * el token, redactar y enviar el correo) ocurre en segundo plano: en la peticion solo se valida el texto, se busca la
 * cuenta (una consulta, exista o no) y se audita. Asi ni la respuesta ni su duracion delatan si el correo esta
 * registrado.
 *
 * <p>Limites (independientes del bloqueo del login): una IP agotada o una cuenta limitada (espera entre solicitudes o
 * maximo diario) no emiten token ni mandan correo; la solicitud termina igual que cualquier otra y queda auditada con
 * su motivo. Un correo mal formado no consume cupo.
 *
 * <p>Un correo mal formado es un 400 que depende solo del texto. Un fallo de la base de datos al buscar la cuenta
 * falla cerrado (503). Los fallos posteriores (token, SMTP, cola llena) no llegan al cliente: quedan auditados.
 */
@Service
public class ServicioRecuperacion {

    private static final Logger log = LoggerFactory.getLogger(ServicioRecuperacion.class);

    private static final String CAMPO_EMAIL = "email";

    private final RepositorioUsuario repositorioUsuario;
    private final ServicioTokensRecuperacion tokens;
    private final PlantillasCorreo plantillas;
    private final NotificadorCorreo notificador;
    private final AuditoriaSeguridad auditoria;
    private final LimitadorRecuperacion limitador;

    public ServicioRecuperacion(RepositorioUsuario repositorioUsuario, ServicioTokensRecuperacion tokens,
            PlantillasCorreo plantillas, NotificadorCorreo notificador, AuditoriaSeguridad auditoria,
            LimitadorRecuperacion limitador) {
        this.repositorioUsuario = repositorioUsuario;
        this.tokens = tokens;
        this.plantillas = plantillas;
        this.notificador = notificador;
        this.auditoria = auditoria;
        this.limitador = limitador;
    }

    public void solicitar(SolicitudRecuperacionDTO solicitud, ContextoPeticion contexto) {
        String email = Normalizador.email(solicitud == null ? null : solicitud.email());
        validar(email);

        // La IP se comprueba la primera, antes de buscar la cuenta: una IP agotada no genera mas trabajo
        if (descartarSiLimitada(limitador.registrarSolicitudDeIp(contexto.ip()), contexto, email)) {
            return;
        }
        Usuario usuario = buscar(email);
        if (usuario == null) {
            auditoria.registrarRecuperacionEmailDesconocido(contexto, email);
            return;
        }
        tratarCuenta(usuario, email, contexto);
    }

    /*
     * Solo las cuentas que pueden recibir un correo (ACTIVO y DESACTIVADO) consumen cupo de cuenta; las demas solo
     * consumieron el de la IP.
     */
    private void tratarCuenta(Usuario usuario, String email, ContextoPeticion contexto) {
        EstadoUsuario estado = usuario.getEstado();
        if (estado != EstadoUsuario.ACTIVO && estado != EstadoUsuario.DESACTIVADO) {
            auditoria.registrarRecuperacionCuentaNoApta(contexto, email, estado);
            return;
        }
        if (descartarSiLimitada(limitador.registrarSolicitudDeCuenta(email), contexto, email)) {
            return;
        }
        if (estado == EstadoUsuario.ACTIVO) {
            auditoria.registrarRecuperacionSolicitada(contexto, email, usuario.getId());
            enviarEnlace(usuario);
        } else {
            auditoria.registrarRecuperacionCuentaNoActiva(contexto, email);
            enviarAvisoDeCuentaPendiente(usuario);
        }
    }

    // Si hay un limite, queda auditado con su motivo. @return true si la solicitud debe descartarse.
    private boolean descartarSiLimitada(Optional<MotivoLimiteRecuperacion> limite, ContextoPeticion contexto,
            String email) {
        limite.ifPresent(motivo -> auditoria.registrarRecuperacionLimitada(contexto, email, motivo));
        return limite.isPresent();
    }

    // Mismas reglas del correo que el registro y el login. Solo depende del texto recibido.
    private static void validar(String email) {
        ErroresValidacion errores = new ErroresValidacion();
        ReglasCampos.validarEmail(CAMPO_EMAIL, email, errores);
        if (errores.hayErrores()) {
            throw new DatosRecuperacionInvalidosException(errores.comoMapa());
        }
    }

    // Falla cerrado: sin causa ni detalles en la excepcion (quedan solo en el log).
    private Usuario buscar(String email) {
        try {
            return repositorioUsuario.buscarPorEmail(email).orElse(null);
        } catch (RuntimeException e) {
            log.error("Fallo interno al buscar la cuenta en la solicitud de recuperacion", e);
            throw new ServicioNoDisponibleException(null);
        }
    }

    private void enviarEnlace(Usuario usuario) {
        String destinatario = usuario.getEmail();
        String idUsuario = usuario.getId();
        notificador.notificarDiferido(destinatario, () -> plantillas.recuperacion(destinatario,
                tokens.emitir(idUsuario).token(), ServicioTokensRecuperacion.VALIDEZ));
    }

    private void enviarAvisoDeCuentaPendiente(Usuario usuario) {
        String destinatario = usuario.getEmail();
        notificador.notificarDiferido(destinatario, () -> plantillas.cuentaPendiente(destinatario));
    }
}
