package com.esibuy.esibuy_backend.configuracion;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * Esqueleto (de momento deja pasar la peticion sin hacer nada).
 *
 * TODO: contador por IP, compartido por las rutas /api/auth/registro/*; al superar maxPeticiones dentro de
 * la ventana, responder 429 con la cabecera Retry-After (segundos). Registrarlo como filtro de la aplicacion
 * leyendo el maximo de la propiedad esibuy.limite-registro.max-peticiones. Tests: CP-SEG-13.
 */
public class FiltroLimitePeticionesRegistro extends OncePerRequestFilter {

    private final int maxPeticiones;
    private final Duration ventana;

    public FiltroLimitePeticionesRegistro(int maxPeticiones, Duration ventana) {
        this.maxPeticiones = maxPeticiones;
        this.ventana = ventana;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        filterChain.doFilter(request, response);
    }
}
