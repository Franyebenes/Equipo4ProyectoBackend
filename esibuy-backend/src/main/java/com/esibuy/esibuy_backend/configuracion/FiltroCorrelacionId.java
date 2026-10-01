package com.esibuy.esibuy_backend.configuracion;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Esqueleto (de momento deja pasar la peticion sin hacer nada).
 *
 * TODO: generar un correlationId, ponerlo en el MDC (clave "correlationId"), devolverlo en la cabecera
 * X-Correlation-Id y limpiar el MDC al terminar. Tests: CP-CTR-05 y CP-REG-12.
 */
public class FiltroCorrelacionId extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        filterChain.doFilter(request, response);
    }
}
