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

import com.esibuy.esibuy_backend.servicio.LimitadorRecuperacion;
import com.esibuy.esibuy_backend.servicio.LimitadorRecuperacionEnMemoria;
import com.esibuy.esibuy_backend.servicio.PropiedadesRecuperacion;

/**
 * Enlace de esibuy.recuperacion.* con application.properties y beans del limitador (HdU recuperacion de contrasena,
 * fase 3). Casos: CP-LIM-06 a CP-LIM-09.
 *
 * Fijan los valores acordados con el equipo: 5 solicitudes al dia por cuenta, 60 s de espera entre solicitudes de la
 * misma cuenta y 10 solicitudes por hora por IP. Se pueden cambiar por configuracion sin tocar codigo, y un valor
 * absurdo impide arrancar.
 *
 * Terminada: ya no lleva la etiqueta pendiente-recuperacion y entra en la regresion.
 */
class LimitesRecuperacionConfiguracionTest {

    @Configuration
    static class RelojDePrueba {
        @Bean
        Clock reloj() {
            return Clock.fixed(Instant.parse("2026-10-17T10:00:00Z"), ZoneId.of("Europe/Madrid"));
        }
    }

    private ApplicationContextRunner arrancar() {
        return new ApplicationContextRunner()
                .withUserConfiguration(RelojDePrueba.class, ConfiguracionRecuperacion.class)
                .withInitializer(new ConfigDataApplicationContextInitializer());
    }

    @Test
    void arrancar_conLaConfiguracionPorDefecto_usaLosLimitesAcordados() { // CP-LIM-06
        arrancar().run(contexto -> {
            assertThat(contexto).hasNotFailed();
            PropiedadesRecuperacion propiedades = contexto.getBean(PropiedadesRecuperacion.class);
            assertThat(propiedades.maxPorCuenta()).isEqualTo(5);
            assertThat(propiedades.ventanaCuenta()).isEqualTo(Duration.ofHours(24));
            assertThat(propiedades.cooldown()).isEqualTo(Duration.ofSeconds(60));
            assertThat(propiedades.maxPorIp()).isEqualTo(10);
            assertThat(propiedades.ventanaIp()).isEqualTo(Duration.ofHours(1));
        });
    }

    @Test
    void arrancar_creaElLimitadorEnMemoria() { // CP-LIM-07
        arrancar().run(contexto -> {
            assertThat(contexto).hasSingleBean(LimitadorRecuperacion.class);
            assertThat(contexto.getBean(LimitadorRecuperacion.class)).isInstanceOf(LimitadorRecuperacionEnMemoria.class);
        });
    }

    @Test
    void arrancar_conValoresPropios_losEnlaza() { // CP-LIM-08
        arrancar().withPropertyValues(
                "esibuy.recuperacion.max-por-cuenta=3",
                "esibuy.recuperacion.ventana-cuenta=PT12H",
                "esibuy.recuperacion.cooldown=PT2M",
                "esibuy.recuperacion.max-por-ip=20",
                "esibuy.recuperacion.ventana-ip=PT30M").run(contexto -> {
                    assertThat(contexto).hasNotFailed();
                    PropiedadesRecuperacion propiedades = contexto.getBean(PropiedadesRecuperacion.class);
                    assertThat(propiedades.maxPorCuenta()).isEqualTo(3);
                    assertThat(propiedades.ventanaCuenta()).isEqualTo(Duration.ofHours(12));
                    assertThat(propiedades.cooldown()).isEqualTo(Duration.ofMinutes(2));
                    assertThat(propiedades.maxPorIp()).isEqualTo(20);
                    assertThat(propiedades.ventanaIp()).isEqualTo(Duration.ofMinutes(30));
                });
    }

    @Test
    void arrancar_conUnValorAbsurdo_noArranca() { // CP-LIM-09
        arrancar().withPropertyValues("esibuy.recuperacion.max-por-cuenta=0")
                .run(contexto -> assertThat(contexto).hasFailed());
        arrancar().withPropertyValues("esibuy.recuperacion.cooldown=PT0S")
                .run(contexto -> assertThat(contexto).hasFailed());
        // Espera mayor que la ventana diaria
        arrancar().withPropertyValues("esibuy.recuperacion.cooldown=PT25H")
                .run(contexto -> assertThat(contexto).hasFailed());
    }
}
