package com.esibuy.esibuy_backend.seguridad;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import com.esibuy.esibuy_backend.modelo.Rol;
import com.esibuy.esibuy_backend.servicio.ResultadoAutenticacion;

/**
 * El usuario autenticado se guarda en la sesión para poder responder a GET /api/auth/me.
 *
 * Clase añadida porque EstablecedorSesionTest pertenece a una fase cerrada y no se modifica.
 */
class EstablecedorSesionUsuarioTest {

    private final EstablecedorSesion establecedor = new EstablecedorSesion(new PoliticaSesion(),
            Clock.fixed(Instant.parse("2026-10-06T10:00:00Z"), ZoneId.of("Europe/Madrid")));

    @Test
    void establecer_loginCorrecto_guardaElUsuarioEnLaSesion() {
        // Given
        ResultadoAutenticacion usuario = new ResultadoAutenticacion("id-1", "ana@ejemplo.es", "Ana", Rol.VENDEDOR);
        MockHttpServletRequest peticion = new MockHttpServletRequest();

        // When
        establecedor.establecer(peticion, usuario);

        // Then
        assertThat(peticion.getSession(false).getAttribute(EstablecedorSesion.ATRIBUTO_USUARIO)).isEqualTo(usuario);
    }

    @Test
    void establecer_conSesionPreviaDeOtroUsuario_noHeredaElUsuarioAnterior() {
        // Given: una sesión previa que guardaba a otro usuario
        MockHttpSession previa = new MockHttpSession();
        previa.setAttribute(EstablecedorSesion.ATRIBUTO_USUARIO,
                new ResultadoAutenticacion("otro", "otro@ejemplo.es", "Otro", Rol.ADMIN));
        MockHttpServletRequest peticion = new MockHttpServletRequest();
        peticion.setSession(previa);
        ResultadoAutenticacion nuevo = new ResultadoAutenticacion("id-2", "luis@ejemplo.es", "Luis", Rol.CLIENTE);

        // When
        establecedor.establecer(peticion, nuevo);

        // Then: la sesión nueva guarda al usuario que acaba de entrar
        assertThat(peticion.getSession(false).getAttribute(EstablecedorSesion.ATRIBUTO_USUARIO)).isEqualTo(nuevo);
    }
}
