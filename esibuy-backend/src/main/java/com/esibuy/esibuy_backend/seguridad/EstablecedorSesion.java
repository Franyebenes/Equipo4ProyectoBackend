package com.esibuy.esibuy_backend.seguridad;

import java.time.Clock;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

import com.esibuy.esibuy_backend.modelo.Rol;
import com.esibuy.esibuy_backend.modelo.Usuario;
import com.esibuy.esibuy_backend.servicio.ResultadoAutenticacion;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/*
 * Crea la sesion del usuario tras un login correcto. Si ya habia una sesion (anonima o de otro usuario) la invalida y
 * crea otra nueva, sin ninguno de sus atributos, para evitar la fijacion de sesion. La sesion nueva guarda el contexto
 * de seguridad autenticado (principal: id del usuario, sin credenciales y con una unica autoridad ROLE_xxx), el
 * instante de inicio, el rol y la inactividad maxima del rol.
 */
public class EstablecedorSesion {

    /** Atributo de sesion con el usuario autenticado (ResultadoAutenticacion), para responder a GET /api/auth/me. */
    public static final String ATRIBUTO_USUARIO = "esibuy.sesion.usuario";

    private static final String PREFIJO_ROL = "ROLE_";

    private final PoliticaSesion politica;
    private final Clock reloj;

    public EstablecedorSesion(PoliticaSesion politica, Clock reloj) {
        this.politica = politica;
        this.reloj = reloj;
    }

    public void establecer(HttpServletRequest peticion, ResultadoAutenticacion resultado) {
        HttpSession sesionAnterior = peticion.getSession(false);
        if (sesionAnterior != null) {
            sesionAnterior.invalidate();
        }
        HttpSession sesion = peticion.getSession(true);

        Rol rol = resultado.rol();
        Authentication autenticacion = UsernamePasswordAuthenticationToken.authenticated(
                resultado.id(), null, List.of(new SimpleGrantedAuthority(PREFIJO_ROL + rol.name())));
        sesion.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(autenticacion));
        sesion.setAttribute(PoliticaSesion.ATRIBUTO_INICIO, reloj.instant());
        sesion.setAttribute(PoliticaSesion.ATRIBUTO_ROL, rol);
        sesion.setAttribute(ATRIBUTO_USUARIO, resultado);
        sesion.setMaxInactiveInterval((int) politica.inactividadMaxima(rol).toSeconds());
    }

    /**
     * Reescribe en la sesion actual el rol y los datos publicos del usuario, sin volver a iniciar sesion.
     * Se usa tras modificar el perfil (nombre, avatar) o cambiar entre CLIENTE y PREMIUM.
     */
    public void actualizar(HttpServletRequest peticion, Usuario usuario) {
        HttpSession sesion = peticion.getSession(false);
        if (sesion == null) {
            return; // Las rutas que lo usan exigen sesion, asi que no deberia ocurrir
        }
        peticion.changeSessionId(); // Nuevo id, mismos atributos (token CSRF e inicio de sesion incluidos)

        Rol rol = usuario.getRoles().get(0);
        Authentication autenticacion = UsernamePasswordAuthenticationToken.authenticated(
                usuario.getId(), null, List.of(new SimpleGrantedAuthority(PREFIJO_ROL + rol.name())));
        SecurityContextImpl contexto = new SecurityContextImpl(autenticacion);
        SecurityContextHolder.setContext(contexto); // Para el resto de esta peticion
        sesion.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, contexto);
        sesion.setAttribute(PoliticaSesion.ATRIBUTO_ROL, rol);
        sesion.setAttribute(ATRIBUTO_USUARIO, new ResultadoAutenticacion(usuario.getId(), usuario.getEmail(),
                usuario.getPerfil().getNombre(), rol, usuario.getPerfil().getAvatarUrl()));
        sesion.setMaxInactiveInterval((int) politica.inactividadMaxima(rol).toSeconds());
    }
}
