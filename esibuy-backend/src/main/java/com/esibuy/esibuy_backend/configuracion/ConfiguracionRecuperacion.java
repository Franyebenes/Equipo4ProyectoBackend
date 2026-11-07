package com.esibuy.esibuy_backend.configuracion;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.esibuy.esibuy_backend.servicio.LimitadorRecuperacion;
import com.esibuy.esibuy_backend.servicio.LimitadorRecuperacionEnMemoria;
import com.esibuy.esibuy_backend.servicio.PropiedadesRecuperacion;

/*
 * Beans de los limites de la recuperacion de contrasena: propiedades (esibuy.recuperacion.*) y limitador en memoria.
 * Tests: LimitesRecuperacionConfiguracionTest.
 */
@Configuration
@EnableConfigurationProperties(PropiedadesRecuperacion.class)
public class ConfiguracionRecuperacion {

    @Bean
    public LimitadorRecuperacion limitadorRecuperacion(PropiedadesRecuperacion propiedades, Clock reloj) {
        return new LimitadorRecuperacionEnMemoria(propiedades, reloj);
    }
}
