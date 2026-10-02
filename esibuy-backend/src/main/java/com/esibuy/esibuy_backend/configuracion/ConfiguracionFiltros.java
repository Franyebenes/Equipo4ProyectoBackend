package com.esibuy.esibuy_backend.configuracion;

import java.time.Clock;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

// Registro de los filtros propios. El de correlacion va el primero para que todas las trazas y respuesta (incluidas las 429 del limite de peticiones) lleven correlationId. Ambos van antes de Spring Security.

@Configuration
public class ConfiguracionFiltros {

    @Bean
    public FilterRegistrationBean<FiltroCorrelacionId> filtroCorrelacionId() {
        FilterRegistrationBean<FiltroCorrelacionId> registro = new FilterRegistrationBean<>(new FiltroCorrelacionId());
        registro.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registro;
    }

    @Bean
    public FilterRegistrationBean<FiltroLimitePeticionesRegistro> filtroLimitePeticionesRegistro(
            @Value("${esibuy.limite-registro.max-peticiones}") int maxPeticiones,
            @Value("${esibuy.limite-registro.ventana:PT1M}") Duration ventana,
            Clock reloj) {
        FilterRegistrationBean<FiltroLimitePeticionesRegistro> registro = new FilterRegistrationBean<>(
                new FiltroLimitePeticionesRegistro(maxPeticiones, ventana, reloj));
        registro.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registro;
    }
}
