package com.esibuy.esibuy_backend.servicio;

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
 * Solicitud de recuperacion de contrasena. Tests: ServicioRecuperacionTest (CP-REC-01 a CP-REC-13).
 *
 * <p>Segun el estado de la cuenta: ACTIVO recibe un enlace con un token de un solo uso; DESACTIVADO (pendiente de
 * activacion) recibe un aviso sin enlace; BLOQUEADO, cualquier otro estado y los correos no registrados no reciben
 * nada. Hacia fuera el metodo es igual en todos los casos (devuelve sin mas), y lo que depende de la cuenta (emitir
 * el token, redactar y enviar el correo) ocurre en segundo plano: en la peticion solo se valida el texto, se busca la
 * cuenta (una consulta, exista o no) y se audita. Asi ni la respuesta ni su duracion delatan si el correo esta
 * registrado.
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

    public ServicioRecuperacion(RepositorioUsuario repositorioUsuario, ServicioTokensRecuperacion tokens,
            PlantillasCorreo plantillas, NotificadorCorreo notificador, AuditoriaSeguridad auditoria) {
        this.repositorioUsuario = repositorioUsuario;
        this.tokens = tokens;
        this.plantillas = plantillas;
        this.notificador = notificador;
        this.auditoria = auditoria;
    }

    public void solicitar(SolicitudRecuperacionDTO solicitud, ContextoPeticion contexto) {
        String email = Normalizador.email(solicitud == null ? null : solicitud.email());
        validar(email);

        Usuario usuario = buscar(email);
        if (usuario == null) {
            auditoria.registrarRecuperacionEmailDesconocido(contexto, email);
            return;
        }

        EstadoUsuario estado = usuario.getEstado();
        if (estado == EstadoUsuario.ACTIVO) {
            auditoria.registrarRecuperacionSolicitada(contexto, email, usuario.getId());
            enviarEnlace(usuario);
        } else if (estado == EstadoUsuario.DESACTIVADO) {
            auditoria.registrarRecuperacionCuentaNoActiva(contexto, email);
            enviarAvisoDeCuentaPendiente(usuario);
        } else {
            auditoria.registrarRecuperacionCuentaNoApta(contexto, email, estado);
        }
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
