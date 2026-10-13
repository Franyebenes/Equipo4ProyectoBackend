package com.esibuy.esibuy_backend.util;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Captura todo lo que se escribe en el log mientras esta abierto (usar con try-with-resources).
 */
public final class CapturadorLogs implements AutoCloseable {

    private final Logger loggerRaiz = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    private final Level nivelOriginal = loggerRaiz.getLevel();
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private CapturadorLogs() {
        appender.start();
        loggerRaiz.setLevel(Level.ALL);
        loggerRaiz.addAppender(appender);
    }

    public static CapturadorLogs iniciar() {
        return new CapturadorLogs();
    }

    public List<ILoggingEvent> eventos() {
        return new ArrayList<>(appender.list);
    }

    /**
     * Devuelve, para cada evento, todo el texto que podria filtrar informacion: mensaje formateado,
     * argumentos, MDC y traza completa de la excepcion (con sus causas).
     */
    public List<String> textosCompletos() {
        List<String> textos = new ArrayList<>();
        for (ILoggingEvent evento : appender.list) {
            StringBuilder texto = new StringBuilder(evento.getFormattedMessage());
            if (evento.getArgumentArray() != null) {
                texto.append(' ').append(java.util.Arrays.toString(evento.getArgumentArray()));
            }
            texto.append(' ').append(evento.getMDCPropertyMap());
            if (evento.getThrowableProxy() != null) {
                texto.append(' ').append(ThrowableProxyUtil.asString(evento.getThrowableProxy()));
            }
            textos.add(texto.toString());
        }
        return textos;
    }

    @Override
    public void close() {
        loggerRaiz.detachAppender(appender);
        loggerRaiz.setLevel(nivelOriginal);
        appender.stop();
    }
}
