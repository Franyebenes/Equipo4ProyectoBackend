package com.esibuy.esibuy_backend.controlador;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.esibuy.esibuy_backend.dto.PerfilDTO;
import com.esibuy.esibuy_backend.dto.SolicitudModificacionPerfilDTO;
import com.esibuy.esibuy_backend.modelo.Usuario;
import com.esibuy.esibuy_backend.seguridad.EstablecedorSesion;
import com.esibuy.esibuy_backend.servicio.ServicioBajaCuenta;
import com.esibuy.esibuy_backend.servicio.ServicioPerfil;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;

/**
 * Perfil del usuario de la sesion . El usuario es siempre el de la sesion (el principal es su
 * id): nunca se recibe por parametro, asi que nadie puede ver ni cambiar el perfil de otro.
 */
@RestController
@RequestMapping("/api/perfil")
public class ControladorPerfil {

    private final ServicioPerfil servicioPerfil;
    private final EstablecedorSesion establecedorSesion;
    private final ServicioBajaCuenta servicioBajaCuenta;

    public ControladorPerfil(ServicioPerfil servicioPerfil, EstablecedorSesion establecedorSesion,
                             ServicioBajaCuenta servicioBajaCuenta) {
        this.servicioPerfil = servicioPerfil;
        this.establecedorSesion = establecedorSesion;
        this.servicioBajaCuenta = servicioBajaCuenta;
    }

    // Ver perfil
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public PerfilDTO verPerfil(Authentication autenticacion) {
        return servicioPerfil.obtenerPerfil(autenticacion.getName());
    }

    // Modificar perfil. Refresca la sesion para que la cabecera muestre el nombre y el avatar nuevos
    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public PerfilDTO modificarPerfil(@Valid @RequestBody SolicitudModificacionPerfilDTO solicitud,
                                     Authentication autenticacion, HttpServletRequest peticion) {
        Usuario usuario = servicioPerfil.modificarPerfil(autenticacion.getName(), solicitud);
        establecedorSesion.actualizar(peticion, usuario);
        return PerfilDTO.desde(usuario);
    }

    // Darse de baja. Las demas sesiones del usuario las invalida FiltroSesionTrasCambioContrasena al no existir ya
    @DeleteMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> darseDeBaja(Authentication autenticacion, HttpServletRequest peticion) {
        servicioBajaCuenta.darDeBaja(autenticacion.getName());
        HttpSession sesion = peticion.getSession(false);
        if (sesion != null) {
            sesion.invalidate();
        }
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }
}