package com.esibuy.esibuy_backend.controlador;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.esibuy.esibuy_backend.dto.UsuarioActualDTO;
import com.esibuy.esibuy_backend.seguridad.EstablecedorSesion;
import com.esibuy.esibuy_backend.servicio.ResultadoAutenticacion;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * Consulta y cierre de la sesion actual (GET /api/auth/me y POST /api/auth/logout). El frontend usa /me para
 * recuperar al usuario tras recargar la pagina. Tests: ControladorSesionTest.
 */
@RestController
@RequestMapping("/api/auth")
public class ControladorSesion {

    /** Usuario de la sesion autenticada, o 401 si no hay sesion o es anonima (p. ej. la que solo guarda el CSRF). */
    @GetMapping("/me")
    public ResponseEntity<UsuarioActualDTO> usuarioActual(HttpServletRequest peticion) {
        HttpSession sesion = peticion.getSession(false);
        if (sesion == null
                || !(sesion.getAttribute(EstablecedorSesion.ATRIBUTO_USUARIO) instanceof ResultadoAutenticacion usuario)) {
            return ResponseEntity.status(401).build();
        }
        return ResponseEntity.ok(new UsuarioActualDTO(usuario.id(), usuario.email(), usuario.nombre(), usuario.rol()));
    }

    /** Invalida la sesion. Exige token CSRF y es idempotente: sin sesion tambien responde 204. */
    @PostMapping("/logout")
    public ResponseEntity<Void> cerrarSesion(HttpServletRequest peticion) {
        HttpSession sesion = peticion.getSession(false);
        if (sesion != null) {
            sesion.invalidate();
        }
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }
}
