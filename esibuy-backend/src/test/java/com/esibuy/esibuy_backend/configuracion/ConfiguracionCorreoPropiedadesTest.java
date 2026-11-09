package com.esibuy.esibuy_backend.configuracion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import com.esibuy.esibuy_backend.servicio.ModoTls;
import com.esibuy.esibuy_backend.servicio.PropiedadesCorreo;

/**
 * Enlace de esibuy.correo.* con los ficheros application.properties y application-prod.properties reales
 * (HdU recuperacion de contrasena, fase 0). Casos: CP-COR-25 a CP-COR-30.
 *
 * Lo que fijan: en desarrollo la configuracion apunta por defecto a un servidor de pruebas local (Mailpit) sin
 * cifrado; en produccion NO hay valores por defecto, asi que olvidar el SMTP impide arrancar en lugar de enviar por
 * un servidor equivocado, y el cifrado por defecto, si no se indica otro, es STARTTLS.
 *
 * Terminada: ya no lleva la etiqueta pendiente-recuperacion y entra en la regresion.
 */
class ConfiguracionCorreoPropiedadesTest {

    @Configuration
    @EnableConfigurationProperties(PropiedadesCorreo.class)
    static class SoloPropiedades {
    }

    /** Carga application.properties (y application-prod.properties si se activa el perfil) como lo hace la app. */
    private ApplicationContextRunner arrancar(String... perfiles) {
        return new ApplicationContextRunner()
                .withUserConfiguration(SoloPropiedades.class)
                .withInitializer(contexto -> contexto.getEnvironment().setActiveProfiles(perfiles))
                .withInitializer(new ConfigDataApplicationContextInitializer());
    }

    private static String[] propiedadesDeProduccion() {
        return new String[] {
                "esibuy.correo.host=smtp.ejemplo.es",
                "esibuy.correo.puerto=587",
                "esibuy.correo.usuario=usuario-smtp",
                "esibuy.correo.contrasena=clave-smtp",
                "esibuy.correo.remitente=no-reply@esibuy.es",
                "esibuy.correo.url-base-frontend=https://esibuy.es" };
    }

    @Test
    void arrancar_sinPerfil_usaPorDefectoUnServidorDePruebasLocalSinCifrado() { // CP-COR-25
        arrancar().run(contexto -> {
            assertThat(contexto).hasNotFailed();
            PropiedadesCorreo propiedades = contexto.getBean(PropiedadesCorreo.class);
            assertThat(propiedades.host()).isEqualTo("localhost");
            assertThat(propiedades.puerto()).isEqualTo(1025);
            assertThat(propiedades.tls()).isEqualTo(ModoTls.NINGUNO);
            assertThat(propiedades.urlBaseFrontend()).startsWith("http://localhost");
            assertThat(propiedades.remitente()).isNotBlank();
        });
    }

    @Test
    void arrancar_conTodosLosValores_losEnlazaCorrectamente() { // CP-COR-26
        arrancar().withPropertyValues(
                "esibuy.correo.host=smtp.ejemplo.es",
                "esibuy.correo.puerto=465",
                "esibuy.correo.tls=TLS_IMPLICITO",
                "esibuy.correo.usuario=usuario-smtp",
                "esibuy.correo.contrasena=clave-smtp",
                "esibuy.correo.remitente=no-reply@esibuy.es",
                "esibuy.correo.url-base-frontend=https://esibuy.es",
                "esibuy.correo.tiempo-conexion=PT3S",
                "esibuy.correo.tiempo-lectura=PT7S").run(contexto -> {
                    assertThat(contexto).hasNotFailed();
                    PropiedadesCorreo propiedades = contexto.getBean(PropiedadesCorreo.class);
                    assertThat(propiedades.host()).isEqualTo("smtp.ejemplo.es");
                    assertThat(propiedades.puerto()).isEqualTo(465);
                    assertThat(propiedades.tls()).isEqualTo(ModoTls.TLS_IMPLICITO);
                    assertThat(propiedades.usuario()).isEqualTo("usuario-smtp");
                    assertThat(propiedades.remitente()).isEqualTo("no-reply@esibuy.es");
                    assertThat(propiedades.tiempoConexion()).isEqualTo(Duration.ofSeconds(3));
                    assertThat(propiedades.tiempoLectura()).isEqualTo(Duration.ofSeconds(7));
                });
    }

    @Test
    void arrancar_sinIndicarElCifradoEnProduccion_elPorDefectoEsStarttls() { // CP-COR-27
        arrancar("prod").withPropertyValues(propiedadesDeProduccion()).run(contexto -> {
            assertThat(contexto).hasNotFailed();
            assertThat(contexto.getBean(PropiedadesCorreo.class).tls()).isEqualTo(ModoTls.STARTTLS);
        });
    }

    @Test
    void arrancar_perfilProdSinConfigurarElSmtp_noArranca() { // CP-COR-28
        // Sin las variables de entorno del SMTP no hay valores por defecto que lo salven
        arrancar("prod").run(contexto -> assertThat(contexto).hasFailed());
    }

    @Test
    void arrancar_perfilProdConLosValoresDelSmtp_arranca() { // CP-COR-29
        arrancar("prod").withPropertyValues(propiedadesDeProduccion()).run(contexto -> {
            assertThat(contexto).hasNotFailed();
            PropiedadesCorreo propiedades = contexto.getBean(PropiedadesCorreo.class);
            assertThat(propiedades.host()).isEqualTo("smtp.ejemplo.es");
            assertThat(propiedades.urlBaseFrontend()).isEqualTo("https://esibuy.es");
        });
    }

    @Test
    void arrancar_modoDeCifradoDesconocido_noArranca() { // CP-COR-30
        arrancar().withPropertyValues("esibuy.correo.tls=SIN_CIFRAR").run(contexto -> assertThat(contexto).hasFailed());
    }

    @Test
    void arrancar_perfilProdConCifradoDesactivadoHaciaUnServidorRemoto_noArranca() { // CP-COR-30
        // Un despliegue con una variable mal puesta no puede degradar el cifrado en silencio
        arrancar("prod").withPropertyValues(propiedadesDeProduccion())
                .withPropertyValues("esibuy.correo.tls=NINGUNO")
                .run(contexto -> assertThat(contexto).hasFailed());
    }
}
