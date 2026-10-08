package com.esibuy.esibuy_backend.servicio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import com.esibuy.esibuy_backend.configuracion.FiltroCorrelacionId;

/*
 * Eventos de auditoria y alertas de seguridad del inicio de sesion. Una linea por evento, con una palabra clave y los
 * datos en forma clave=valor. El correo siempre va enmascarado y los textos que controla el cliente, saneados; nunca
 * se escribe una contrasena ni un hash. Tambien es el {@link AlertasSeguridad} real del limitador de login.
 */
@Component
public class AuditoriaSeguridad implements AlertasSeguridad {

    private static final Logger log = LoggerFactory.getLogger(AuditoriaSeguridad.class);

    public void registrarLoginCorrecto(ContextoPeticion contexto, String correo, String idUsuario) {
        log.info("LOGIN_CORRECTO {} userId={}", datosDeLaPeticion(contexto, correo),
                EnmascaradorDatosLog.texto(idUsuario));
    }

    /** El motivo interno solo queda en este evento: la respuesta al cliente es siempre la misma. */
    public void registrarLoginFallido(ContextoPeticion contexto, String correo, MotivoFalloLogin motivo) {
        log.warn("LOGIN_FALLIDO {} motivo={}", datosDeLaPeticion(contexto, correo), motivo);
    }

    @Override
    public void ipEnModoAdaptativo(String ip, int usuariosDistintos) {
        log.warn("ALERTA_SEGURIDAD tipo=IP_MODO_ADAPTATIVO ip={} usuarios={}", EnmascaradorDatosLog.texto(ip),
                usuariosDistintos);
    }

    @Override
    public void cuentaBloqueada(String correo, String ip, long segundosDeBloqueo) {
        log.warn("ALERTA_SEGURIDAD tipo=BLOQUEO_CUENTA email={} ip={} segundos={}",
                EnmascaradorDatosLog.correo(correo), EnmascaradorDatosLog.texto(ip), segundosDeBloqueo);
    }

    private static String datosDeLaPeticion(ContextoPeticion contexto, String correo) {
        return "ip=" + EnmascaradorDatosLog.texto(contexto.ip())
                + " userAgent=" + EnmascaradorDatosLog.texto(contexto.userAgent())
                + " correlationId=" + EnmascaradorDatosLog.texto(MDC.get(FiltroCorrelacionId.CLAVE_MDC))
                + " email=" + EnmascaradorDatosLog.correo(correo);
    }
}
