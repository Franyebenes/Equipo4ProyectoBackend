package com.esibuy.esibuy_backend.servicio;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import org.slf4j.MDC;

/*
 * Punto por el que los servicios de negocio mandan correos. El envio va en segundo plano y ningun fallo llega al
 * llamante: asi la respuesta al cliente no depende de si el correo existe ni de que el SMTP vaya lento o falle (lo que
 * permitiria distinguir cuentas registradas por el tiempo o por el error). Los fallos se auditan con
 * CORREO_ENVIO_FALLIDO. El MDC (correlationId) de la peticion se copia al hilo de envio y se restaura despues.
 * Tests: NotificadorCorreoTest (CP-COR-70 a CP-COR-77).
 */
public class NotificadorCorreo {

    private final EnviadorCorreo enviador;
    private final Executor ejecutor;
    private final AuditoriaSeguridad auditoria;

    public NotificadorCorreo(EnviadorCorreo enviador, Executor ejecutor, AuditoriaSeguridad auditoria) {
        this.enviador = enviador;
        this.ejecutor = ejecutor;
        this.auditoria = auditoria;
    }

    /** Encola el envio y vuelve de inmediato. Nunca lanza excepciones por fallos de envio. */
    public void notificar(MensajeCorreo mensaje) {
        notificarDiferido(mensaje.destinatario(), () -> mensaje);
    }

    /*
     * Como {@link #notificar}, pero el mensaje se prepara tambien en segundo plano (por ejemplo, emitiendo antes un
     * token en la base de datos): asi esa preparacion no alarga la respuesta a la peticion. Si la preparacion o el
     * envio fallan, no se propaga y se audita. Tests: NotificadorCorreoDiferidoTest (CP-REC-20 a CP-REC-24).
     */
    public void notificarDiferido(String destinatario, Supplier<MensajeCorreo> preparacion) {
        Map<String, String> contexto = MDC.getCopyOfContextMap();
        try {
            ejecutor.execute(() -> enviar(destinatario, preparacion, contexto));
        } catch (RuntimeException e) { // cola llena o ejecutor parado
            auditoria.registrarFalloEnvioCorreo(destinatario, e);
        }
    }

    private void enviar(String destinatario, Supplier<MensajeCorreo> preparacion,
            Map<String, String> contextoDeLaPeticion) {
        Map<String, String> contextoPrevio = MDC.getCopyOfContextMap();
        aplicarContexto(contextoDeLaPeticion);
        try {
            enviador.enviar(preparacion.get());
        } catch (RuntimeException e) {
            auditoria.registrarFalloEnvioCorreo(destinatario, e);
        } finally {
            aplicarContexto(contextoPrevio);
        }
    }

    private static void aplicarContexto(Map<String, String> contexto) {
        if (contexto == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(contexto);
        }
    }
}
