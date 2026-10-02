package com.esibuy.esibuy_backend.seguridad;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.esibuy.esibuy_backend.configuracion.FiltroCorrelacionId;
import com.esibuy.esibuy_backend.modelo.Rol;

/**
 * Seguridad web, CORS y RBAC. Solo son publicas las rutas que se enumeran; todo lo demas exige autenticacion (denegado por defecto).
 */
@Configuration
@EnableWebSecurity
public class ConfiguracionSeguridad {

    @Bean
    public SecurityFilterChain cadenaFiltrosSeguridad(HttpSecurity http) {
        http
                .cors(Customizer.withDefaults())
                // API REST sin estado y sin cookies de sesion: no hay sesion que un CSRF pueda aprovechar
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sesion -> sesion.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(autorizacion -> autorizacion
                        .requestMatchers(HttpMethod.OPTIONS, "/api/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/registro/**", "/api/public/**").permitAll()
                        .anyRequest().authenticated());
        return http.build();
    }

    //Spring Security localiza este bean por su nombre (corsConfigurationSource): no renombrar.
    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origin:http://localhost:43127}") String origenPermitido) {
        CorsConfiguration configuracion = new CorsConfiguration();
        configuracion.setAllowedOrigins(List.of(origenPermitido));
        configuracion.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuracion.setAllowedHeaders(List.of("*"));
        // El frontend necesita leer el correlationId para poder citarlo al reportar un error
        configuracion.setExposedHeaders(List.of(FiltroCorrelacionId.CABECERA, HttpHeaders.RETRY_AFTER));

        UrlBasedCorsConfigurationSource origen = new UrlBasedCorsConfigurationSource();
        origen.registerCorsConfiguration("/api/**", configuracion);
        return origen;
    }

    // Un usuario tiene un unico rol: PREMIUM hereda los permisos de CLIENTE para no repetir
    @Bean
    public RoleHierarchy jerarquiaRoles() {
        return RoleHierarchyImpl.withDefaultRolePrefix()
                .role(Rol.PREMIUM.name()).implies(Rol.CLIENTE.name())
                .build();
    }
}
