package com.esibuy.esibuy_backend.modelo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class UsuarioCambioTipoClienteTest {

    private static Usuario usuarioCon(Rol rol) {
        PerfilUsuario perfil = PerfilUsuario.builder().nombre("Ana").apellidos("Lopez").build();
        return new Usuario("ana@gmail.com", "$argon2id$hash", rol, perfil);
    }

    @Test
    void cambiarTipoCliente_clienteAPremium_quedaComoPremium() {
        Usuario usuario = usuarioCon(Rol.CLIENTE);

        usuario.cambiarTipoCliente(Rol.PREMIUM);

        assertThat(usuario.getRoles()).containsExactly(Rol.PREMIUM);
    }

    @Test
    void cambiarTipoCliente_premiumACliente_quedaComoCliente() {
        Usuario usuario = usuarioCon(Rol.PREMIUM);

        usuario.cambiarTipoCliente(Rol.CLIENTE);

        assertThat(usuario.getRoles()).containsExactly(Rol.CLIENTE);
    }

    @ParameterizedTest
    @EnumSource(value = Rol.class, names = {"VENDEDOR", "ADMIN"})
    void cambiarTipoCliente_clienteARolNoCliente_lanzaYNoCambia(Rol destino) {
        Usuario usuario = usuarioCon(Rol.CLIENTE);

        assertThatThrownBy(() -> usuario.cambiarTipoCliente(destino)).isInstanceOf(IllegalStateException.class);
        assertThat(usuario.getRoles()).containsExactly(Rol.CLIENTE);
    }

    @ParameterizedTest
    @EnumSource(value = Rol.class, names = {"VENDEDOR", "ADMIN"})
    void cambiarTipoCliente_usuarioNoCliente_lanza(Rol origen) {
        Usuario usuario = usuarioCon(origen);

        assertThatThrownBy(() -> usuario.cambiarTipoCliente(Rol.PREMIUM)).isInstanceOf(IllegalStateException.class);
    }
}