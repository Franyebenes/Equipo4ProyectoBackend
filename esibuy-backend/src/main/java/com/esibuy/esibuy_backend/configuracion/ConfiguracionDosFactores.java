package com.esibuy.esibuy_backend.configuracion;

import java.time.Clock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.esibuy.esibuy_backend.servicio.CifradorSecretos;
import com.esibuy.esibuy_backend.servicio.GeneradorCodigosRecuperacion;
import com.esibuy.esibuy_backend.servicio.GeneradorTotp;
import com.esibuy.esibuy_backend.servicio.PropiedadesDosFactores;

/*
 * Beans del segundo factor (2FA con TOTP): propiedades (esibuy.dosfactores.*), cifrador de secretos, generador TOTP con
 * el reloj de la aplicacion y generador de codigos de recuperacion con el pepper. Sin pepper la aplicacion no arranca,
 * igual que con las contrasenas.
 * Tests: ConfiguracionDosFactoresTest.
 */
@Configuration
@EnableConfigurationProperties(PropiedadesDosFactores.class)
public class ConfiguracionDosFactores {

    @Bean
    public CifradorSecretos cifradorSecretos(PropiedadesDosFactores propiedades) {
        return new CifradorSecretos(propiedades.clavesEnBytes(), propiedades.versionActiva());
    }

    @Bean
    public GeneradorTotp generadorTotp(Clock reloj) {
        return new GeneradorTotp(reloj);
    }

    @Bean
    public GeneradorCodigosRecuperacion generadorCodigosRecuperacion(@Value("${esibuy.seguridad.pepper:}") String pepper) {
        return new GeneradorCodigosRecuperacion(pepper);
    }
}
