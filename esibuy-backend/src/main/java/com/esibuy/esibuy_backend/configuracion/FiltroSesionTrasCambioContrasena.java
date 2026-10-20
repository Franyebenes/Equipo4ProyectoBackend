package com.esibuy.esibuy_backend.configuracion;

import java.io.IOException;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import com.esibuy.esibuy_backend.seguridad.PoliticaSesion;
import com.esibuy.esibuy_backend.servicio.ValidadorSesionUsuario;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/*
 * Invalida las sesiones iniciadas antes de que su usuario cambiara la contrasena (o cuyo usuario ya no existe): es lo
 * que cumple "se invalidan todas las sesiones abiertas" al restablecer la contrasena. En cada peticion con una sesion
 * autenticada pregunta a {@link ValidadorSesionUsuario}; si ya no vale, invalida la sesion y responde 401 con el mismo
 * cuerpo que la caducidad de sesion (FiltroCaducidadSesion). Una sesion autenticada sin instante de inicio tampoco vale.
 *
 * No toca las peticiones sin sesion ni las de sesiones anonimas (por ejemplo la que solo guarda el token CSRF), ni las
 * de /api/auth/** salvo /api/auth/me: asi una sesion antigua no estorba al iniciar sesion de nuevo, al recuperar la
 * contrasena ni al cerrar sesion. Si la base de datos falla responde 503 SIN invalidar la sesion: un fallo temporal no
 * debe cerrar la sesion de nadie.
 * Tests: FiltroSesionTrasCambioContrasenaTest (CP-RST-40 a CP-RST-46).
 */
public class FiltroSesionTrasCambioContrasena extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(FiltroSesionTrasCambioContrasena.class);

    private static final String PREFIJO_AUTH = "/api/auth/";
    private static final String RUTA_ME = "/api/auth/me";
    private static final String CUERPO_SESION_CADUCADA = "{\"mensaje\":\"La sesion ha caducado\"}";
    private static final String CUERPO_NO_DISPONIBLE =
            "{\"mensaje\":\"El servicio no esta disponible en este momento. Vuelve a intentarlo mas tarde\"}";

    private final ValidadorSesionUsuario validador;

    public FiltroSesionTrasCambioContrasena(ValidadorSesionUsuario validador) {
        this.validador = validador;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String ruta = request.getRequestURI();
        return ruta.startsWith(PREFIJO_AUTH) && !ruta.equals(RUTA_ME);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        HttpSession sesion = request.getSession(false);
        if (sesion != null && sesion.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)
                instanceof SecurityContext contextoSeguridad) {
            switch (comprobarVigencia(sesion, contextoSeguridad)) {
                case CADUCADA -> {
                    sesion.invalidate();
                    responder(response, HttpStatus.UNAUTHORIZED, CUERPO_SESION_CADUCADA);
                    return;
                }
                case NO_COMPROBABLE -> {
                    responder(response, HttpStatus.SERVICE_UNAVAILABLE, CUERPO_NO_DISPONIBLE);
                    return;
                }
                case VIGENTE -> {
                    // sigue su curso
                }
            }
        }
        filterChain.doFilter(request, response);
    }

    private enum Vigencia {
        VIGENTE, CADUCADA, NO_COMPROBABLE
    }

    private Vigencia comprobarVigencia(HttpSession sesion, SecurityContext contextoSeguridad) {
        if (!(sesion.getAttribute(PoliticaSesion.ATRIBUTO_INICIO) instanceof Instant inicio)) {
            return Vigencia.CADUCADA;
        }
        Authentication autenticacion = contextoSeguridad.getAuthentication();
        String idUsuario = autenticacion == null ? null : autenticacion.getName();
        try {
            return validador.sigueVigente(idUsuario, inicio) ? Vigencia.VIGENTE : Vigencia.CADUCADA;
        } catch (RuntimeException e) {
            // Solo el nombre de la clase: el mensaje de una excepcion de la base de datos puede traer datos
            log.error("Fallo al comprobar la vigencia de una sesion: {}", e.getClass().getSimpleName());
            return Vigencia.NO_COMPROBABLE;
        }
    }

    private static void responder(HttpServletResponse response, HttpStatus estado, String cuerpo) throws IOException {
        response.setStatus(estado.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(cuerpo);
    }
}
