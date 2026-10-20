package com.esibuy.esibuy_backend.servicio;

import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import com.esibuy.esibuy_backend.configuracion.FiltroCorrelacionId;
import com.esibuy.esibuy_backend.excepcion.CodigoError;
import com.esibuy.esibuy_backend.modelo.EstadoUsuario;

/*
 * Eventos de auditoria y alertas de seguridad del inicio de sesion. Una linea por evento, con una palabra clave y los
 * datos en forma clave=valor. El correo siempre va enmascarado y los textos que controla el cliente, saneados; nunca
 * se escribe una contrasena ni un hash. Tambien es el {@link AlertasSeguridad} real del limitador de login.
 * Cada evento comprueba antes si su nivel de log esta activo, para no enmascarar ni sanear datos que no se van a escribir.
 */
@Component
public class AuditoriaSeguridad implements AlertasSeguridad {

    private static final Logger log = LoggerFactory.getLogger(AuditoriaSeguridad.class);

    public void registrarLoginCorrecto(ContextoPeticion contexto, String correo, String idUsuario) {
        if (log.isInfoEnabled()) {
            log.info("LOGIN_CORRECTO {} userId={}", datosDeLaPeticion(contexto, correo),
                    EnmascaradorDatosLog.texto(idUsuario));
        }
    }

    /*
     * Contrasena correcta pero cuenta aun sin activar. No es un login fallido (no suma al limitador ni sale como
     * LOGIN_FALLIDO), pero queda constancia: el cliente recibe la respuesta generica y este evento es el unico lugar
     * donde se ve por que no pudo entrar.
     */
    public void registrarLoginCuentaNoActiva(ContextoPeticion contexto, String correo) {
        if (log.isInfoEnabled()) {
            log.info("LOGIN_CUENTA_NO_ACTIVA {}", datosDeLaPeticion(contexto, correo));
        }
    }

    /*
     * Un correo no pudo entregarse. Solo se escribe el NOMBRE de la clase del fallo: su mensaje puede traer
     * direcciones, servidores o credenciales, y el cuerpo del correo lleva el token de recuperacion.
     */
    public void registrarFalloEnvioCorreo(String destinatario, Throwable causa) {
        if (log.isWarnEnabled()) {
            log.warn("CORREO_ENVIO_FALLIDO email={} causa={} correlationId={}", EnmascaradorDatosLog.correo(destinatario),
                    causa == null ? "desconocida" : causa.getClass().getSimpleName(),
                    EnmascaradorDatosLog.texto(MDC.get(FiltroCorrelacionId.CLAVE_MDC)));
        }
    }

    // Contrasena restablecida con un enlace valido. Tests: CP-RST-50.
    public void registrarRestablecimientoCorrecto(ContextoPeticion contexto, String correo, String idUsuario) {
        if (log.isInfoEnabled()) {
            log.info("RESTABLECIMIENTO_CORRECTO {} userId={}", datosDeLaPeticion(contexto, correo),
                    EnmascaradorDatosLog.texto(idUsuario));
        }
    }

    // Enlace que no vale (desconocido, caducado, anulado). No hay correo: el token no es de nadie. Tests: CP-RST-51.
    public void registrarRestablecimientoTokenInvalido(ContextoPeticion contexto, ResultadoToken.Motivo motivo) {
        if (log.isWarnEnabled()) {
            log.warn("RESTABLECIMIENTO_TOKEN_INVALIDO {} motivo={}", datosSinCorreo(contexto),
                    motivo == null ? "(desconocido)" : motivo.name());
        }
    }

    // Alguien usa un enlace que ya se gasto: alerta de seguridad. Tests: CP-RST-52.
    public void registrarRestablecimientoTokenReutilizado(ContextoPeticion contexto) {
        if (log.isWarnEnabled()) {
            log.warn("ALERTA_SEGURIDAD tipo=TOKEN_RECUPERACION_REUTILIZADO {}", datosSinCorreo(contexto));
        }
    }

    // La cuenta del enlace ya no esta activa (estado) o ya no existe (estado nulo). Tests: CP-RST-53.
    public void registrarRestablecimientoCuentaNoApta(ContextoPeticion contexto, String correo, EstadoUsuario estado) {
        if (log.isWarnEnabled()) {
            log.warn("RESTABLECIMIENTO_CUENTA_NO_APTA {} estado={}", datosDeLaPeticion(contexto, correo),
                    estado == null ? "(inexistente)" : estado.name());
        }
    }

    // La contrasena nueva incumple la politica: solo se escriben los codigos, nunca la contrasena. Tests: CP-RST-54.
    public void registrarRestablecimientoContrasenaRechazada(ContextoPeticion contexto, String correo,
            String idUsuario, Set<CodigoError> motivos) {
        if (log.isInfoEnabled()) {
            String codigos = motivos == null ? "(ninguno)"
                    : motivos.stream().map(CodigoError::name).sorted().collect(Collectors.joining(","));
            log.info("RESTABLECIMIENTO_CONTRASENA_RECHAZADA {} userId={} errores={}",
                    datosDeLaPeticion(contexto, correo), EnmascaradorDatosLog.texto(idUsuario), codigos);
        }
    }

    // La IP agoto sus intentos de restablecer. Tests: CP-RST-55.
    public void registrarRestablecimientoLimitado(ContextoPeticion contexto) {
        if (log.isWarnEnabled()) {
            log.warn("RESTABLECIMIENTO_LIMITADO {}", datosSinCorreo(contexto));
        }
    }

    // Solicitud de recuperacion descartada por los limites (el cliente no lo sabe). Tests: CP-LIM-30 a CP-LIM-32. 
    public void registrarRecuperacionLimitada(ContextoPeticion contexto, String correo, MotivoLimiteRecuperacion motivo) {
        if (log.isWarnEnabled()) {
            log.warn("RECUPERACION_LIMITADA {} motivo={}", datosDeLaPeticion(contexto, correo),
                    motivo == null ? "(desconocido)" : motivo.name());
        }
    }

    // Cuenta activa que pide recuperar la contrasena: se le envia el enlace. Tests: CP-REC-30. 
    public void registrarRecuperacionSolicitada(ContextoPeticion contexto, String correo, String idUsuario) {
        if (log.isInfoEnabled()) {
            log.info("RECUPERACION_SOLICITADA {} userId={}", datosDeLaPeticion(contexto, correo),
                    EnmascaradorDatosLog.texto(idUsuario));
        }
    }

    // Cuenta pendiente de activacion: se le envia el aviso, sin enlace. Tests: CP-REC-31. 
    public void registrarRecuperacionCuentaNoActiva(ContextoPeticion contexto, String correo) {
        if (log.isInfoEnabled()) {
            log.info("RECUPERACION_CUENTA_NO_ACTIVA {}", datosDeLaPeticion(contexto, correo));
        }
    }

    // Cuenta bloqueada (u otro estado que no puede recuperar): no se envia nada. Tests: CP-REC-32. 
    public void registrarRecuperacionCuentaNoApta(ContextoPeticion contexto, String correo, EstadoUsuario estado) {
        if (log.isWarnEnabled()) {
            log.warn("RECUPERACION_CUENTA_NO_APTA {} estado={}", datosDeLaPeticion(contexto, correo),
                    estado == null ? "(desconocido)" : estado.name());
        }
    }

    /** Correo no registrado: no se envia nada y el cliente no lo sabe. Tests: CP-REC-33. */
    public void registrarRecuperacionEmailDesconocido(ContextoPeticion contexto, String correo) {
        if (log.isInfoEnabled()) {
            log.info("RECUPERACION_EMAIL_DESCONOCIDO {}", datosDeLaPeticion(contexto, correo));
        }
    }

    // El motivo interno solo queda en este evento: la respuesta al cliente es siempre la misma. */
    public void registrarLoginFallido(ContextoPeticion contexto, String correo, MotivoFalloLogin motivo) {
        if (log.isWarnEnabled()) {
            log.warn("LOGIN_FALLIDO {} motivo={}", datosDeLaPeticion(contexto, correo), motivo);
        }
    }

    @Override
    public void ipEnModoAdaptativo(String ip, int usuariosDistintos) {
        if (log.isWarnEnabled()) {
            log.warn("ALERTA_SEGURIDAD tipo=IP_MODO_ADAPTATIVO ip={} usuarios={}", EnmascaradorDatosLog.texto(ip),
                    usuariosDistintos);
        }
    }

    @Override
    public void cuentaBloqueada(String correo, String ip, long segundosDeBloqueo) {
        if (log.isWarnEnabled()) {
            log.warn("ALERTA_SEGURIDAD tipo=BLOQUEO_CUENTA email={} ip={} segundos={}",
                    EnmascaradorDatosLog.correo(correo), EnmascaradorDatosLog.texto(ip), segundosDeBloqueo);
        }
    }

    // La persona ha pedido el QR para activar el 2FA (aun no esta activo). Tests: CP-2FA-226.
    public void registrarDosFactoresActivacionIniciada(ContextoPeticion contexto, String correo, String idUsuario) {
        if (log.isInfoEnabled()) {
            log.info("DOS_FACTORES_ACTIVACION_INICIADA {} userId={}", datosDeLaPeticion(contexto, correo),
                    EnmascaradorDatosLog.texto(idUsuario));
        }
    }

    // 2FA activado tras confirmar el primer codigo. Tests: CP-2FA-227.
    public void registrarDosFactoresActivado(ContextoPeticion contexto, String correo, String idUsuario) {
        if (log.isInfoEnabled()) {
            log.info("DOS_FACTORES_ACTIVADO {} userId={}", datosDeLaPeticion(contexto, correo),
                    EnmascaradorDatosLog.texto(idUsuario));
        }
    }

    // 2FA desactivado: baja la proteccion de la cuenta, asi que se destaca como aviso. Tests: CP-2FA-228.
    public void registrarDosFactoresDesactivado(ContextoPeticion contexto, String correo, String idUsuario) {
        if (log.isWarnEnabled()) {
            log.warn("DOS_FACTORES_DESACTIVADO {} userId={}", datosDeLaPeticion(contexto, correo),
                    EnmascaradorDatosLog.texto(idUsuario));
        }
    }

    // Codigos de recuperacion sustituidos por otros nuevos. Tests: CP-2FA-229.
    public void registrarDosFactoresCodigosRegenerados(ContextoPeticion contexto, String correo, String idUsuario) {
        if (log.isInfoEnabled()) {
            log.info("DOS_FACTORES_CODIGOS_REGENERADOS {} userId={}", datosDeLaPeticion(contexto, correo),
                    EnmascaradorDatosLog.texto(idUsuario));
        }
    }

    // Alerta: se uso un codigo de recuperacion. Se escribe cuantos quedan, nunca el codigo. Tests: CP-2FA-230.
    public void registrarDosFactoresCodigoRecuperacionUsado(ContextoPeticion contexto, String correo, String idUsuario,
            int quedan) {
        if (log.isWarnEnabled()) {
            log.warn("ALERTA_SEGURIDAD tipo=CODIGO_RECUPERACION_USADO {} userId={} quedan={}",
                    datosDeLaPeticion(contexto, correo), EnmascaradorDatosLog.texto(idUsuario), quedan);
        }
    }

    // Contrasena o codigo incorrectos al operar con el 2FA, o activacion no vigente. Solo el motivo. Tests: CP-2FA-231.
    public void registrarDosFactoresFallido(ContextoPeticion contexto, String correo, String idUsuario,
            MotivoFalloDosFactores motivo) {
        if (log.isWarnEnabled()) {
            log.warn("DOS_FACTORES_FALLIDO {} userId={} motivo={}", datosDeLaPeticion(contexto, correo),
                    EnmascaradorDatosLog.texto(idUsuario), motivo == null ? "(desconocido)" : motivo.name());
        }
    }

    // La cuenta o la IP agotaron sus fallos. Ocurre antes de cargar la cuenta, asi que solo hay id. Tests: CP-2FA-232.
    public void registrarDosFactoresLimitado(ContextoPeticion contexto, String idUsuario) {
        if (log.isWarnEnabled()) {
            log.warn("DOS_FACTORES_LIMITADO {} userId={}", datosSinCorreo(contexto),
                    EnmascaradorDatosLog.texto(idUsuario));
        }
    }

    // Los mismos datos de la peticion cuando no hay correo (el restablecimiento por token desconocido).
    private static String datosSinCorreo(ContextoPeticion contexto) {
        return "ip=" + EnmascaradorDatosLog.texto(contexto.ip())
                + " userAgent=" + EnmascaradorDatosLog.texto(contexto.userAgent())
                + " correlationId=" + EnmascaradorDatosLog.texto(MDC.get(FiltroCorrelacionId.CLAVE_MDC));
    }

    private static String datosDeLaPeticion(ContextoPeticion contexto, String correo) {
        return "ip=" + EnmascaradorDatosLog.texto(contexto.ip())
                + " userAgent=" + EnmascaradorDatosLog.texto(contexto.userAgent())
                + " correlationId=" + EnmascaradorDatosLog.texto(MDC.get(FiltroCorrelacionId.CLAVE_MDC))
                + " email=" + EnmascaradorDatosLog.correo(correo);
    }
}