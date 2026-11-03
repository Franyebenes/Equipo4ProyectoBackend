package com.esibuy.esibuy_backend.configuracion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import java.util.concurrent.ThreadPoolExecutor;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import com.esibuy.esibuy_backend.servicio.AuditoriaSeguridad;
import com.esibuy.esibuy_backend.servicio.EnviadorCorreo;
import com.esibuy.esibuy_backend.servicio.EnviadorCorreoSmtp;
import com.esibuy.esibuy_backend.servicio.ModoTls;
import com.esibuy.esibuy_backend.servicio.NotificadorCorreo;
import com.esibuy.esibuy_backend.servicio.PlantillasCorreo;
import com.esibuy.esibuy_backend.util.PropiedadesCorreoPrueba;

/**
 * Cliente SMTP y beans del correo (HdU recuperacion de contrasena, fase 0). Casos: CP-COR-35 a CP-COR-43.
 *
 * Lo importante es lo que NO se configura: ni se desactiva la verificacion del certificado, ni se confia en cualquier
 * certificado, ni se activa el modo debug de JavaMail (que escribiria usuario y contrasena en el log).
 *
 * Contrato que fijan: ConfiguracionCorreo es una clase @Configuration con
 * @EnableConfigurationProperties(PropiedadesCorreo.class) y los beans JavaMailSender, EnviadorCorreo, PlantillasCorreo,
 * ejecutor acotado y NotificadorCorreo.
 *
 * Terminada: ya no lleva la etiqueta pendiente-recuperacion y entra en la regresion.
 */
class ConfiguracionCorreoTest {

    private final ConfiguracionCorreo configuracion = new ConfiguracionCorreo();

    private JavaMailSenderImpl crear(PropiedadesCorreoPrueba propiedades) {
        return configuracion.javaMailSender(propiedades.construir());
    }

    private static String propiedad(JavaMailSenderImpl emisor, String clave) {
        return emisor.getJavaMailProperties().getProperty(clave);
    }

    // ------------------------------------------------------------------ CP-COR-35 / 36 / 37

    @Test
    void javaMailSender_starttls_exigeElevarLaConexionAVerificandoElCertificado() { // CP-COR-35
        JavaMailSenderImpl emisor = crear(PropiedadesCorreoPrueba.remoto());

        assertThat(emisor.getHost()).isEqualTo("smtp.ejemplo.es");
        assertThat(emisor.getPort()).isEqualTo(587);
        assertThat(emisor.getProtocol()).isEqualTo("smtp");
        assertThat(propiedad(emisor, "mail.smtp.starttls.enable")).isEqualTo("true");
        // required=true: si el servidor no ofrece STARTTLS, el envio falla en vez de seguir en claro
        assertThat(propiedad(emisor, "mail.smtp.starttls.required")).isEqualTo("true");
        assertThat(propiedad(emisor, "mail.smtp.ssl.checkserveridentity")).isEqualTo("true");
    }

    @Test
    void javaMailSender_tlsImplicito_cifraDesdeElPrimerByteVerificandoElCertificado() { // CP-COR-36
        JavaMailSenderImpl emisor = crear(PropiedadesCorreoPrueba.remoto().puerto(465).tls(ModoTls.TLS_IMPLICITO));

        assertThat(emisor.getPort()).isEqualTo(465);
        assertThat(propiedad(emisor, "mail.smtp.ssl.enable")).isEqualTo("true");
        assertThat(propiedad(emisor, "mail.smtp.ssl.checkserveridentity")).isEqualTo("true");
    }

    @Test
    void javaMailSender_sinCifradoHaciaLocalhost_noActivaTls() { // CP-COR-37
        JavaMailSenderImpl emisor = crear(PropiedadesCorreoPrueba.local(1025));

        assertThat(propiedad(emisor, "mail.smtp.starttls.enable")).isNotEqualTo("true");
        assertThat(propiedad(emisor, "mail.smtp.ssl.enable")).isNotEqualTo("true");
        assertThat(propiedad(emisor, "mail.smtp.auth")).isNotEqualTo("true");
    }

    // ------------------------------------------------------------------ CP-COR-38 / 39

    @Test
    void javaMailSender_nuncaDesactivaLasVerificacionesNiActivaElDebug() { // CP-COR-38
        for (PropiedadesCorreoPrueba variante : new PropiedadesCorreoPrueba[] {
                PropiedadesCorreoPrueba.remoto(),
                PropiedadesCorreoPrueba.remoto().puerto(465).tls(ModoTls.TLS_IMPLICITO),
                PropiedadesCorreoPrueba.local(1025) }) {
            Properties todas = crear(variante).getJavaMailProperties();

            // Confiar en todos los certificados anularia el cifrado frente a un atacante en la red
            assertThat(todas.getProperty("mail.smtp.ssl.trust")).isNotEqualTo("*");
            assertThat(todas.getProperty("mail.smtp.ssl.checkserveridentity")).isNotEqualTo("false");
            assertThat(todas).doesNotContainKey("mail.smtp.ssl.socketFactory.class");
            // El debug de JavaMail vuelca la conversacion SMTP, incluidas las credenciales, a la salida estandar
            assertThat(todas.getProperty("mail.debug")).isNotEqualTo("true");
        }
    }

    @Test
    void javaMailSender_conTls_soloAdmiteTls12Y13() { // CP-COR-39
        assertThat(propiedad(crear(PropiedadesCorreoPrueba.remoto()), "mail.smtp.ssl.protocols"))
                .isEqualTo("TLSv1.2 TLSv1.3");
        assertThat(propiedad(crear(PropiedadesCorreoPrueba.remoto().puerto(465).tls(ModoTls.TLS_IMPLICITO)),
                "mail.smtp.ssl.protocols")).isEqualTo("TLSv1.2 TLSv1.3");
    }

    // ------------------------------------------------------------------ CP-COR-40 / 41

    @Test
    void javaMailSender_conCredenciales_autenticaConEllas() { // CP-COR-40
        JavaMailSenderImpl emisor = crear(PropiedadesCorreoPrueba.remoto());

        assertThat(propiedad(emisor, "mail.smtp.auth")).isEqualTo("true");
        assertThat(emisor.getUsername()).isEqualTo("usuario-smtp");
        assertThat(emisor.getPassword()).isEqualTo(PropiedadesCorreoPrueba.CONTRASENA_SMTP);
    }

    @Test
    void javaMailSender_aplicaLosTiemposDeEsperaYLaCodificacionUtf8() { // CP-COR-41
        JavaMailSenderImpl emisor = crear(PropiedadesCorreoPrueba.remoto()
                .tiempoConexion(java.time.Duration.ofSeconds(3)).tiempoLectura(java.time.Duration.ofSeconds(7)));

        assertThat(propiedad(emisor, "mail.smtp.connectiontimeout")).isEqualTo("3000");
        assertThat(propiedad(emisor, "mail.smtp.timeout")).isEqualTo("7000");
        assertThat(propiedad(emisor, "mail.smtp.writetimeout")).isEqualTo("7000");
        assertThat(emisor.getDefaultEncoding()).isEqualTo("UTF-8");
    }

    // ------------------------------------------------------------------ CP-COR-42

    @Test
    void ejecutorCorreo_estaAcotadoYRechazaEnLugarDeAcumularSinLimite() { // CP-COR-42
        ThreadPoolExecutor ejecutor = configuracion.ejecutorCorreo();
        try {
            // Una cola sin limite permitiria agotar la memoria pidiendo recuperaciones en masa
            assertThat(ejecutor.getQueue().remainingCapacity()).isBetween(1, 1000);
            assertThat(ejecutor.getMaximumPoolSize()).isBetween(1, 8);
            // Al llenarse lanza RejectedExecutionException, que el NotificadorCorreo audita
            assertThat(ejecutor.getRejectedExecutionHandler()).isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
        } finally {
            ejecutor.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ CP-COR-43

    static class Dependencias {
        @Bean
        AuditoriaSeguridad auditoriaSeguridad() {
            return new AuditoriaSeguridad();
        }
    }

    @Test
    void contexto_conUnaConfiguracionValida_creaTodosLosBeansSinConectarAlServidor() { // CP-COR-43
        new ApplicationContextRunner()
                .withUserConfiguration(Dependencias.class, ConfiguracionCorreo.class)
                // Un servidor que no existe: el arranque no debe conectar con el SMTP
                .withPropertyValues(
                        "esibuy.correo.host=smtp.no-existe.invalid",
                        "esibuy.correo.puerto=587",
                        "esibuy.correo.usuario=usuario-smtp",
                        "esibuy.correo.contrasena=clave-smtp",
                        "esibuy.correo.remitente=no-reply@esibuy.es",
                        "esibuy.correo.url-base-frontend=https://esibuy.es")
                .run(contexto -> {
                    assertThat(contexto).hasNotFailed();
                    assertThat(contexto).hasSingleBean(JavaMailSender.class);
                    assertThat(contexto.getBean(EnviadorCorreo.class)).isInstanceOf(EnviadorCorreoSmtp.class);
                    assertThat(contexto).hasSingleBean(PlantillasCorreo.class);
                    assertThat(contexto).hasSingleBean(NotificadorCorreo.class);
                });
    }
}
