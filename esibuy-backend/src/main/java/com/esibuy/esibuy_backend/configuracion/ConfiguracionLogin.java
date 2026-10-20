package com.esibuy.esibuy_backend.configuracion;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.esibuy.esibuy_backend.seguridad.EstablecedorSesion;
import com.esibuy.esibuy_backend.seguridad.GestorDesafioSegundoFactor;
import com.esibuy.esibuy_backend.seguridad.PoliticaSesion;
import com.esibuy.esibuy_backend.servicio.ResolutorIpCliente;

/*
 * Beans de la capa web del inicio de sesion: politica y establecedor de sesion, y resolutor de la IP del cliente.
 * Los proxies de confianza se configuran con esibuy.seguridad.proxies-confiables (lista separada por comas; vacia por
 * defecto: no se fia de ninguna cabecera X-Forwarded-For).
 */
@Configuration
public class ConfiguracionLogin {

    @Bean
    public PoliticaSesion politicaSesion() {
        return new PoliticaSesion();
    }

    @Bean
    public EstablecedorSesion establecedorSesion(PoliticaSesion politica, Clock reloj) {
        return new EstablecedorSesion(politica, reloj);
    }

    /*
     * Desafio del segundo paso del login. Lee de la misma configuracion que PropiedadesDosFactores (que es la que valida
     * los valores al arrancar), sin depender de ese bean: asi la capa web del login se puede probar sola.
     */
    @Bean
    public GestorDesafioSegundoFactor gestorDesafioSegundoFactor(
            @Value("${esibuy.dosfactores.caducidad-desafio}") Duration caducidad,
            @Value("${esibuy.dosfactores.max-intentos-desafio}") int maxIntentos, Clock reloj) {
        return new GestorDesafioSegundoFactor(caducidad, maxIntentos, reloj);
    }

    @Bean
    public ResolutorIpCliente resolutorIpCliente(
            @Value("${esibuy.seguridad.proxies-confiables:}") List<String> proxiesConfiables) {
        return new ResolutorIpCliente(proxiesConfiables);
    }
}
