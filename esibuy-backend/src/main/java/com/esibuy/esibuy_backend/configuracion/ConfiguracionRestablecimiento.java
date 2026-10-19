package com.esibuy.esibuy_backend.configuracion;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.esibuy.esibuy_backend.servicio.LimitadorRestablecimiento;
import com.esibuy.esibuy_backend.servicio.PropiedadesRestablecimiento;

/*
 * Beans del limite de intentos de restablecer la contrasena: propiedades (esibuy.restablecimiento.*) y limitador en
 * memoria. Tests: LimitadorRestablecimientoTest.
 */
@Configuration
@EnableConfigurationProperties(PropiedadesRestablecimiento.class)
public class ConfiguracionRestablecimiento {

    @Bean
    public LimitadorRestablecimiento limitadorRestablecimiento(PropiedadesRestablecimiento propiedades, Clock reloj) {
        return new LimitadorRestablecimiento(propiedades, reloj);
    }
}
