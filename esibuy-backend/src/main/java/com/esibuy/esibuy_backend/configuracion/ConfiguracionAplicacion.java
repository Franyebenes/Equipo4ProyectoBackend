package com.esibuy.esibuy_backend.configuracion;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.esibuy.esibuy_backend.servicio.PropiedadesAvatares;

// Beans generales de la aplicacion: reloj inyectable (en los tests se sustituye por uno fijo) y propiedades.
@Configuration
@EnableConfigurationProperties(PropiedadesAvatares.class)
public class ConfiguracionAplicacion {

    @Bean
    public Clock reloj() {
        return Clock.system(ZoneId.of("Europe/Madrid"));
    }
}
