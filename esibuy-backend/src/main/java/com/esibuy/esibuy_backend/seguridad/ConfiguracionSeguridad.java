package com.esibuy.esibuy_backend.seguridad;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;

/**
 * Seguridad web y RBAC. Tests: ControladorAuthTest (CP-SEG-07), JerarquiaRolesTest (CP-SEG-12).
 *
 * TODO:
 *  - Publicar un SecurityFilterChain (@Bean): POST /api/auth/registro/** y GET /api/registro/** publicos y
 *    sin CSRF; el resto, denegado por defecto.
 *  - Convertir jerarquiaRoles() en @Bean con "ROLE_PREMIUM > ROLE_CUSTOMER" (un usuario tiene un unico rol).
 */
@Configuration
public class ConfiguracionSeguridad {

    public RoleHierarchy jerarquiaRoles() {
        throw new UnsupportedOperationException("Pendiente de implementar");
    }
}
