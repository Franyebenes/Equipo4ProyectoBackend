package com.esibuy.esibuy_backend.configuracion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.esibuy.esibuy_backend.servicio.LimitadorRestablecimiento;
import com.esibuy.esibuy_backend.servicio.PropiedadesRestablecimiento;

/**
 * Enlace de esibuy.restablecimiento.* con application.properties (HdU recuperacion de contrasena, fase 4).
 * Casos: CP-RST-29 y CP-RST-29b. Fijan el valor acordado (10 intentos por hora por IP), configurable.
 *
 * Terminada: ya no lleva la etiqueta pendiente-recuperacion y entra en la regresion.
 */
class RestablecimientoConfiguracionTest {

    @Configuration
    static class RelojDePrueba {
        @Bean
        Clock reloj() {
            return Clock.fixed(Instant.parse("2026-10-19T10:00:00Z"), ZoneId.of("Europe/Madrid"));
        }
    }

    private ApplicationContextRunner arrancar() {
        return new ApplicationContextRunner()
                .withUserConfiguration(RelojDePrueba.class, ConfiguracionRestablecimiento.class)
                .withInitializer(new ConfigDataApplicationContextInitializer());
    }

    @Test
    void arrancar_conLaConfiguracionPorDefecto_usaElLimiteAcordadoYCreaElLimitador() { // CP-RST-29
        arrancar().run(contexto -> {
            assertThat(contexto).hasNotFailed();
            PropiedadesRestablecimiento propiedades = contexto.getBean(PropiedadesRestablecimiento.class);
            assertThat(propiedades.maxPorIp()).isEqualTo(10);
            assertThat(propiedades.ventanaIp()).isEqualTo(Duration.ofHours(1));
            assertThat(contexto).hasSingleBean(LimitadorRestablecimiento.class);
        });
    }

    @Test
    void arrancar_conValoresPropiosOAbsurdos_losEnlazaOFalla() { // CP-RST-29
        arrancar().withPropertyValues("esibuy.restablecimiento.max-por-ip=3", "esibuy.restablecimiento.ventana-ip=PT30M")
                .run(contexto -> {
                    assertThat(contexto).hasNotFailed();
                    assertThat(contexto.getBean(PropiedadesRestablecimiento.class).maxPorIp()).isEqualTo(3);
                    assertThat(contexto.getBean(PropiedadesRestablecimiento.class).ventanaIp())
                            .isEqualTo(Duration.ofMinutes(30));
                });
        arrancar().withPropertyValues("esibuy.restablecimiento.max-por-ip=0")
                .run(contexto -> assertThat(contexto).hasFailed());
    }
}
