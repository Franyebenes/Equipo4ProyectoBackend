package com.esibuy.esibuy_backend.configuracion;

import java.time.Clock;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registro de los filtros propios y su orden:
 * <ol>
 *   <li>Correlacion: el primero, para que todas las trazas y respuestas (incluidas las 429 del limite de
 *       peticiones) lleven correlationId.</li>
 *   <li>Spring Security, con su filtro CORS.</li>
 *   <li>Limite de peticiones: justo despues de Spring Security. Si fuera antes, su respuesta 429 no pasaria
 *       por el filtro CORS y no llevaria las cabeceras Access-Control-*: el navegador la bloquearia y el
 *       frontend veria un error de red en lugar de un 429 con su Retry-After. Test: LimitePeticionesRegistroCorsTest.</li>
 * </ol>
 */
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
        registro.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER + 1);
        return registro;
    }
}
