package com.esibuy.esibuy_backend.configuracion;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/** Reloj inyectable (en los tests se sustituye por uno fijo). */
@Configuration
public class ConfiguracionAplicacion {

    @Bean
    public Clock reloj() {
        return Clock.system(ZoneId.of("Europe/Madrid"));
    }
}
