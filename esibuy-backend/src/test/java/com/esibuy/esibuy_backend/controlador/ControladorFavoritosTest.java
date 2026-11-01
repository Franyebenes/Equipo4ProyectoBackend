package com.esibuy.esibuy_backend.controlador;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.esibuy.esibuy_backend.dto.Disponibilidad;
import com.esibuy.esibuy_backend.dto.ProductoClienteDTO;
import com.esibuy.esibuy_backend.excepcion.FavoritoNoEncontradoException;
import com.esibuy.esibuy_backend.excepcion.FavoritoRepetidoException;
import com.esibuy.esibuy_backend.seguridad.ConfiguracionSeguridad;
import com.esibuy.esibuy_backend.servicio.ServicioFavoritos;

/** HU-31 Favoritos. /api/favoritos: solo clientes y premium, siempre los del cliente de la sesion. */
@WebMvcTest(ControladorFavoritos.class)
@Import(ConfiguracionSeguridad.class)
class ControladorFavoritosTest {

    private static final String ID_CLIENTE = "64b7f0c2a1b2c3d4e5f60001";
    private static final String ID_TAZA = "64b7f0c2a1b2c3d4e5f60101";
    private static final String RUTA = "/api/favoritos";
    private static final String RUTA_TAZA = RUTA + "/" + ID_TAZA;

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private ServicioFavoritos servicioFavoritos;

    @Test
    @WithMockUser(username = ID_CLIENTE, roles = "CLIENTE")
    void listar_cliente_devuelveSusFavoritosConLaInformacionDelCatalogo() throws Exception {
        when(servicioFavoritos.listar(ID_CLIENTE, false)).thenReturn(List.of(new ProductoClienteDTO(ID_TAZA, "Taza",
                null, List.of("Cocina"), 4.0, 4.0, false, Disponibilidad.DISPONIBLE, null, 0)));

        mockMvc.perform(get(RUTA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].nombre").value("Taza"))
                .andExpect(jsonPath("$[0].categorias[0]").value("Cocina"))
                .andExpect(jsonPath("$[0].disponibilidad").value("DISPONIBLE"))
                .andExpect(jsonPath("$[0].stock").doesNotExist());
    }

    @Test
    @WithMockUser(username = ID_CLIENTE, roles = "PREMIUM")
    void listar_premium_pideLosPreciosConSuDescuento() throws Exception {
        when(servicioFavoritos.listar(ID_CLIENTE, true)).thenReturn(List.of());

        mockMvc.perform(get(RUTA)).andExpect(status().isOk());
        verify(servicioFavoritos).listar(ID_CLIENTE, true);
    }

    @Test
    @WithMockUser(username = ID_CLIENTE, roles = "CLIENTE")
    void ids_devuelveLosIdsDeSusFavoritos() throws Exception {
        when(servicioFavoritos.idsFavoritos(ID_CLIENTE)).thenReturn(List.of(ID_TAZA));

        mockMvc.perform(get(RUTA + "/ids")).andExpect(status().isOk()).andExpect(jsonPath("$[0]").value(ID_TAZA));
    }

    @Test
    @WithMockUser(username = ID_CLIENTE, roles = "CLIENTE")
    void anadir_devuelve201YUsaElClienteDeLaSesion() throws Exception {
        mockMvc.perform(post(RUTA_TAZA).with(csrf())).andExpect(status().isCreated());
        verify(servicioFavoritos).anadir(ID_CLIENTE, ID_TAZA);
    }

    @Test
    @WithMockUser(username = ID_CLIENTE, roles = "CLIENTE")
    void anadir_yaEraFavorito_devuelve409ConMensaje() throws Exception {
        doThrow(new FavoritoRepetidoException()).when(servicioFavoritos).anadir(ID_CLIENTE, ID_TAZA);

        mockMvc.perform(post(RUTA_TAZA).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensaje").value("Este producto ya está en tus favoritos"));
    }

    @Test
    @WithMockUser(username = ID_CLIENTE, roles = "CLIENTE")
    void anadir_sinTokenCsrf_devuelve403() throws Exception {
        mockMvc.perform(post(RUTA_TAZA)).andExpect(status().isForbidden());
        verify(servicioFavoritos, never()).anadir(anyString(), anyString());
    }

    @Test
    void anadir_sinIniciarSesion_devuelve401() throws Exception {
        mockMvc.perform(post(RUTA_TAZA).with(csrf())).andExpect(status().isUnauthorized());
        verify(servicioFavoritos, never()).anadir(anyString(), anyString());
    }

    @Test
    @WithMockUser(roles = "VENDEDOR")
    void listar_vendedor_devuelve403() throws Exception {
        mockMvc.perform(get(RUTA)).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = ID_CLIENTE, roles = "CLIENTE")
    void eliminar_devuelve204() throws Exception {
        mockMvc.perform(delete(RUTA_TAZA).with(csrf())).andExpect(status().isNoContent());
        verify(servicioFavoritos).eliminar(ID_CLIENTE, ID_TAZA);
    }

    @Test
    @WithMockUser(username = ID_CLIENTE, roles = "CLIENTE")
    void eliminar_noEstabaEnSusFavoritos_devuelve404ConMensaje() throws Exception {
        doThrow(new FavoritoNoEncontradoException()).when(servicioFavoritos).eliminar(ID_CLIENTE, ID_TAZA);

        mockMvc.perform(delete(RUTA_TAZA).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.mensaje").value("Este producto no está en tus favoritos"));
    }
}