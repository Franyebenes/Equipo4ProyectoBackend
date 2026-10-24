package com.esibuy.esibuy_backend.seguridad;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.util.ReflectionTestUtils;

import com.esibuy.esibuy_backend.modelo.PerfilUsuario;
import com.esibuy.esibuy_backend.modelo.Rol;
import com.esibuy.esibuy_backend.modelo.Usuario;
import com.esibuy.esibuy_backend.servicio.ResultadoAutenticacion;

class EstablecedorSesionActualizarTest {

    private static final String ID = "665f1c2e9b1d4a0012345678";

    private final EstablecedorSesion establecedor = new EstablecedorSesion(new PoliticaSesion(), Clock.systemUTC());

    @AfterEach
    void limpiarContexto() {
        SecurityContextHolder.clearContext();
    }

    private static Usuario usuarioPremium() {
        PerfilUsuario perfil = PerfilUsuario.builder().nombre("Ana").apellidos("Lopez").avatarUrl("avatar-03").build();
        Usuario usuario = new Usuario("ana@gmail.com", "$argon2id$hash", Rol.PREMIUM, perfil);
        ReflectionTestUtils.setField(usuario, "id", ID);
        return usuario;
    }

    @Test
    void actualizar_conSesion_rotaElIdYGuardaRolYDatosNuevos() {
        MockHttpServletRequest peticion = new MockHttpServletRequest();
        MockHttpSession sesion = new MockHttpSession();
        sesion.setAttribute(PoliticaSesion.ATRIBUTO_ROL, Rol.CLIENTE);
        peticion.setSession(sesion);
        String idAnterior = sesion.getId();

        establecedor.actualizar(peticion, usuarioPremium());

        assertThat(sesion.getId()).isNotEqualTo(idAnterior);
        assertThat(sesion.getAttribute(PoliticaSesion.ATRIBUTO_ROL)).isEqualTo(Rol.PREMIUM);
        SecurityContext contexto = (SecurityContext) sesion
                .getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(contexto.getAuthentication().getName()).isEqualTo(ID);
        assertThat(contexto.getAuthentication().getAuthorities()).extracting("authority").containsExactly("ROLE_PREMIUM");
        ResultadoAutenticacion datos = (ResultadoAutenticacion) sesion.getAttribute(EstablecedorSesion.ATRIBUTO_USUARIO);
        assertThat(datos.rol()).isEqualTo(Rol.PREMIUM);
        assertThat(datos.avatar()).isEqualTo("avatar-03");
    }

    @Test
    void actualizar_sinSesion_noCreaNinguna() {
        MockHttpServletRequest peticion = new MockHttpServletRequest();

        establecedor.actualizar(peticion, usuarioPremium());

        assertThat(peticion.getSession(false)).isNull();
    }
}