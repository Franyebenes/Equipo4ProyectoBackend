package com.esibuy.esibuy_backend.seguridad;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Consecuencia de la decision D2: un usuario tiene un unico rol y PREMIUM debe heredar los
 * permisos de CUSTOMER mediante una jerarquia de roles (en vez de repetir hasAnyRole en cada endpoint).
 */
class JerarquiaRolesTest {

    private RoleHierarchy jerarquia;

    @BeforeEach
    void prepararJerarquia() {
        jerarquia = new ConfiguracionSeguridad().jerarquiaRoles();
    }

    private List<String> autoridadesAlcanzablesDesde(String rol) {
        Collection<? extends GrantedAuthority> alcanzables =
                jerarquia.getReachableGrantedAuthorities(List.of(new SimpleGrantedAuthority(rol)));
        return alcanzables.stream().map(GrantedAuthority::getAuthority).toList();
    }

    @Test
    void getReachableGrantedAuthorities_usuarioPremium_alcanzaTambienLosPermisosDeCustomer() { // CP-SEG-12
        // Given: un usuario cuyo unico rol es PREMIUM

        // When
        List<String> autoridades = autoridadesAlcanzablesDesde("ROLE_PREMIUM");

        // Then
        assertThat(autoridades).contains("ROLE_PREMIUM", "ROLE_CUSTOMER");
    }

    @Test
    void getReachableGrantedAuthorities_usuarioCustomer_noAlcanzaLosPermisosExclusivosDePremium() { // CP-SEG-12
        // Given: un cliente estandar

        // When
        List<String> autoridades = autoridadesAlcanzablesDesde("ROLE_CUSTOMER");

        // Then
        assertThat(autoridades).contains("ROLE_CUSTOMER").doesNotContain("ROLE_PREMIUM");
    }

    @Test
    void getReachableGrantedAuthorities_vendedorYCliente_noSeHeredanEntreSi() { // CP-SEG-12
        // Given / When
        List<String> desdeVendedor = autoridadesAlcanzablesDesde("ROLE_SELLER");
        List<String> desdeCliente = autoridadesAlcanzablesDesde("ROLE_CUSTOMER");

        // Then: minimo privilegio, ningun rol gana permisos de otro tipo de usuario
        assertThat(desdeVendedor).doesNotContain("ROLE_CUSTOMER", "ROLE_PREMIUM", "ROLE_ADMIN");
        assertThat(desdeCliente).doesNotContain("ROLE_SELLER", "ROLE_ADMIN");
    }
}
