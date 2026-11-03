package com.esibuy.esibuy_backend.seguridad;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

import com.esibuy.esibuy_backend.configuracion.FiltroCorrelacionId;
import com.esibuy.esibuy_backend.modelo.Rol;

/*
 * Seguridad web, CORS y RBAC. Solo son publicas las rutas que se enumeran; todo lo demas exige autenticacion (denegado por defecto).
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity // @PreAuthorize, @Secured, @RolesAllowed
public class ConfiguracionSeguridad {

    /** Un ano: el HSTS que recomienda CCN-CERT BP/28 para que el navegador solo use HTTPS. */
    private static final long SEGUNDOS_HSTS = 31_536_000L;

    @Bean
    public SecurityFilterChain cadenaFiltrosSeguridad(HttpSecurity http) {
        http
                .cors(Customizer.withDefaults())
                // Hay sesion (la crea el login), asi que el login exige token CSRF. El registro no crea sesion ni
                // usa la existente: sigue exento
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/auth/registro"))
                .sessionManagement(sesion -> sesion.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .headers(cabeceras -> cabeceras.httpStrictTransportSecurity(
                        hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(SEGUNDOS_HSTS)))
                // Quien no esta autenticado recibe 401 (y no un 403), sin la cabecera WWW-Authenticate
                .exceptionHandling(errores -> errores
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(autorizacion -> autorizacion
                        .requestMatchers(HttpMethod.OPTIONS, "/api/**").permitAll()
                        // Operaciones del vendedor (su catalogo propio): solo rol VENDEDOR, el resto recibe 403
                        .requestMatchers("/api/vendedor/**").hasRole(Rol.VENDEDOR.name())
                        // Cualquier metodo: un GET llega a MVC y recibe un 405 en lugar de un 403
                        .requestMatchers("/api/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/registro/**", "/api/public/**").permitAll()
                        .requestMatchers("/api/admin/**").hasRole(Rol.ADMIN.name())
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
        // La sesion viaja en una cookie y el frontend esta en otro origen: sin esto el navegador no la envia ni la
        // guarda. Es valido porque el origen permitido es uno concreto (con credenciales no se admite "*")
        configuracion.setAllowCredentials(true);
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
