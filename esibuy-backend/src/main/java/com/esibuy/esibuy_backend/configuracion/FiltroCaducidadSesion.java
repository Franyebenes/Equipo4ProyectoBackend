package com.esibuy.esibuy_backend.configuracion;

import java.io.IOException;
import java.time.Clock;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import com.esibuy.esibuy_backend.seguridad.PoliticaSesion;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/*
 * Invalida las sesiones autenticadas que han superado su caducidad absoluta (8 h o 6 h segun el rol, aunque haya
 * actividad) y responde 401. Solo mira las sesiones con contexto de seguridad: una sesion anonima, por ejemplo la que
 * guarda el token CSRF, no tiene instante de inicio y no se toca. Tests: ControladorLoginTest (CP-SES-08).
 */
public class FiltroCaducidadSesion extends OncePerRequestFilter {

    private static final String CUERPO_SESION_CADUCADA = "{\"mensaje\":\"La sesion ha caducado\"}";

    private final PoliticaSesion politica;
    private final Clock reloj;

    public FiltroCaducidadSesion(PoliticaSesion politica, Clock reloj) {
        this.politica = politica;
        this.reloj = reloj;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        HttpSession sesion = request.getSession(false);
        if (sesion != null && estaAutenticada(sesion) && politica.haCaducado(sesion, reloj.instant())) {
            sesion.invalidate();
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(CUERPO_SESION_CADUCADA);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static boolean estaAutenticada(HttpSession sesion) {
        return sesion.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) != null;
    }
}
