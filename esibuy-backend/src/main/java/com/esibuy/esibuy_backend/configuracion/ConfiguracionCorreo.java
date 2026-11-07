package com.esibuy.esibuy_backend.configuracion;

import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import com.esibuy.esibuy_backend.servicio.AuditoriaSeguridad;
import com.esibuy.esibuy_backend.servicio.EnviadorCorreo;
import com.esibuy.esibuy_backend.servicio.EnviadorCorreoSmtp;
import com.esibuy.esibuy_backend.servicio.ModoTls;
import com.esibuy.esibuy_backend.servicio.NotificadorCorreo;
import com.esibuy.esibuy_backend.servicio.PlantillasCorreo;
import com.esibuy.esibuy_backend.servicio.PropiedadesCorreo;

/**
 * Beans del correo saliente. Con TLS se exige el cifrado (STARTTLS obligatorio, no se vuelve a texto plano),
 * TLS 1.2 o 1.3 y verificacion del certificado y del nombre del servidor. No se confia en cualquier certificado y
 * nunca se activa el modo debug de JavaMail (volcaria usuario y contrasena). El arranque no conecta con el servidor.
 * Tests: ConfiguracionCorreoTest (CP-COR-35 a CP-COR-43).
 *
 * <p>El ejecutor es un bean de tipo Executor, por lo que Spring Boot ya no crea su applicationTaskExecutor por defecto
 * (la aplicacion no usa @Async ni peticiones asincronas de MVC).
 */
@Configuration
@EnableConfigurationProperties(PropiedadesCorreo.class)
public class ConfiguracionCorreo {

    private static final String PROTOCOLOS_TLS = "TLSv1.2 TLSv1.3";
    private static final int HILOS_DE_ENVIO = 2;
    private static final int CAPACIDAD_DE_COLA = 200;

    @Bean
    public JavaMailSenderImpl javaMailSender(PropiedadesCorreo propiedades) {
        JavaMailSenderImpl emisor = new JavaMailSenderImpl();
        emisor.setHost(propiedades.host());
        emisor.setPort(propiedades.puerto());
        emisor.setProtocol("smtp");
        emisor.setDefaultEncoding(StandardCharsets.UTF_8.name());

        Properties ajustes = emisor.getJavaMailProperties();
        ajustes.setProperty("mail.smtp.connectiontimeout", String.valueOf(propiedades.tiempoConexion().toMillis()));
        ajustes.setProperty("mail.smtp.timeout", String.valueOf(propiedades.tiempoLectura().toMillis()));
        ajustes.setProperty("mail.smtp.writetimeout", String.valueOf(propiedades.tiempoLectura().toMillis()));

        if (propiedades.usaAutenticacion()) {
            emisor.setUsername(propiedades.usuario());
            emisor.setPassword(propiedades.contrasena());
            ajustes.setProperty("mail.smtp.auth", "true");
        }
        if (propiedades.tls() == ModoTls.STARTTLS) {
            ajustes.setProperty("mail.smtp.starttls.enable", "true");
            ajustes.setProperty("mail.smtp.starttls.required", "true");
        } else if (propiedades.tls() == ModoTls.TLS_IMPLICITO) {
            ajustes.setProperty("mail.smtp.ssl.enable", "true");
        }
        if (propiedades.tls() != ModoTls.NINGUNO) {
            ajustes.setProperty("mail.smtp.ssl.checkserveridentity", "true");
            ajustes.setProperty("mail.smtp.ssl.protocols", PROTOCOLOS_TLS);
        }
        return emisor;
    }

    @Bean
    public EnviadorCorreo enviadorCorreo(JavaMailSender emisor, PropiedadesCorreo propiedades) {
        return new EnviadorCorreoSmtp(emisor, propiedades);
    }

    @Bean
    public PlantillasCorreo plantillasCorreo(PropiedadesCorreo propiedades) {
        return new PlantillasCorreo(propiedades);
    }

    /**
     * Pocos hilos y cola acotada: pedir recuperaciones en masa no puede agotar la memoria. Al llenarse rechaza la tarea
     * (AbortPolicy) y el NotificadorCorreo lo audita. Hilos demonio para no impedir el cierre de la aplicacion.
     */
    @Bean
    public ThreadPoolExecutor ejecutorCorreo() {
        AtomicInteger contador = new AtomicInteger();
        ThreadFactory fabrica = tarea -> {
            Thread hilo = new Thread(tarea, "correo-" + contador.incrementAndGet());
            hilo.setDaemon(true);
            return hilo;
        };
        return new ThreadPoolExecutor(HILOS_DE_ENVIO, HILOS_DE_ENVIO, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(CAPACIDAD_DE_COLA), fabrica, new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean
    public NotificadorCorreo notificadorCorreo(EnviadorCorreo enviador,
            @Qualifier("ejecutorCorreo") Executor ejecutorCorreo, AuditoriaSeguridad auditoria) {
        return new NotificadorCorreo(enviador, ejecutorCorreo, auditoria);
    }
}
