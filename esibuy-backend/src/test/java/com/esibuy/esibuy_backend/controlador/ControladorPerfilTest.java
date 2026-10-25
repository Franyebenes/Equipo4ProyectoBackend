package com.esibuy.esibuy_backend.controlador;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.esibuy.esibuy_backend.dto.PerfilDTO;
import com.esibuy.esibuy_backend.modelo.PerfilUsuario;
import com.esibuy.esibuy_backend.modelo.Rol;
import com.esibuy.esibuy_backend.modelo.Usuario;
import com.esibuy.esibuy_backend.seguridad.ConfiguracionSeguridad;
import com.esibuy.esibuy_backend.seguridad.EstablecedorSesion;
import com.esibuy.esibuy_backend.servicio.ServicioPerfil;

/** HU-32.1 y HU-32.2. GET y PUT /api/perfil: cualquier rol, siempre sobre el usuario de la sesion. */
@WebMvcTest(ControladorPerfil.class)
@Import(ConfiguracionSeguridad.class)
class ControladorPerfilTest {

    private static final String ID = "64b7f0c2a1b2c3d4e5f60001";
    private static final String RUTA = "/api/perfil";
    private static final String CUERPO_VALIDO = "{\"nombre\":\"Ana\",\"apellidos\":\"Lopez\",\"avatar\":\"avatar-01\"}";

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private ServicioPerfil servicioPerfil;
    @MockitoBean
    private EstablecedorSesion establecedorSesion; // No es @Component: lo crea ConfiguracionLogin

    private static Usuario usuario() {
        PerfilUsuario perfil = PerfilUsuario.builder().nombre("Ana").apellidos("Lopez").avatarUrl("avatar-01").build();
        return new Usuario("ana@gmail.com", "$argon2id$hash", Rol.CLIENTE, perfil);
    }

    @Test
    void verPerfil_sinSesion_devuelve401() throws Exception {
        mockMvc.perform(get(RUTA)).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = ID, roles = "VENDEDOR")
    void verPerfil_conSesion_devuelveElPerfilDelUsuarioDeLaSesion() throws Exception {
        when(servicioPerfil.obtenerPerfil(ID)).thenReturn(PerfilDTO.desde(usuario()));

        mockMvc.perform(get(RUTA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("ana@gmail.com"))
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    @WithMockUser(username = ID, roles = "CLIENTE")
    void modificarPerfil_sinTokenCsrf_devuelve403YNoModifica() throws Exception {
        mockMvc.perform(put(RUTA).contentType(MediaType.APPLICATION_JSON).content(CUERPO_VALIDO))
                .andExpect(status().isForbidden());
        verify(servicioPerfil, never()).modificarPerfil(any(), any());
    }

    @Test
    @WithMockUser(username = ID, roles = "CLIENTE")
    void modificarPerfil_nombreVacio_devuelve400YNoModifica() throws Exception {
        mockMvc.perform(put(RUTA).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"\",\"apellidos\":\"Lopez\",\"avatar\":\"avatar-01\"}"))
                .andExpect(status().isBadRequest());
        verify(servicioPerfil, never()).modificarPerfil(any(), any());
    }

    @Test
    @WithMockUser(username = ID, roles = "CLIENTE")
    void modificarPerfil_intentaCambiarElEmail_devuelve400YNoModifica() throws Exception {
        mockMvc.perform(put(RUTA).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"Ana\",\"apellidos\":\"Lopez\",\"avatar\":\"avatar-01\",\"email\":\"otro@gmail.com\"}"))
                .andExpect(status().isBadRequest());
        verify(servicioPerfil, never()).modificarPerfil(any(), any());
    }

    @Test
    @WithMockUser(username = ID, roles = "CLIENTE")
    void modificarPerfil_valido_devuelve200YRefrescaLaSesion() throws Exception {
        Usuario usuario = usuario();
        when(servicioPerfil.modificarPerfil(eq(ID), any())).thenReturn(usuario);

        mockMvc.perform(put(RUTA).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(CUERPO_VALIDO))
                .andExpect(status().isOk());
        verify(establecedorSesion).actualizar(any(), eq(usuario));
    }
}