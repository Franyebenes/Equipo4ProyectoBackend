package com.esibuy.esibuy_backend.configuracion;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

// Asigna a cada peticion un identificador de correlacion: lo pone en el MDC (asi aparece en todas las trazas de la peticion) y lo devuelve en la cabecera X-Correlation-Id. 
// Sieempre se genera en el servidor: aceptar el de la cabecera de entrada permitiria inyectar texto en los logs.
 
public class FiltroCorrelacionId extends OncePerRequestFilter {

    public static final String CLAVE_MDC = "correlationId";
    public static final String CABECERA = "X-Correlation-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String correlationId = UUID.randomUUID().toString();
        MDC.put(CLAVE_MDC, correlationId);
        response.setHeader(CABECERA, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(CLAVE_MDC);
        }
    }
}
