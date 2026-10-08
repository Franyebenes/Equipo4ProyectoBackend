package com.esibuy.esibuy_backend.controlador;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.esibuy.esibuy_backend.excepcion.CategoriaConProductosException;
import com.esibuy.esibuy_backend.excepcion.CategoriaNoEncontradaException;
import com.esibuy.esibuy_backend.seguridad.ConfiguracionSeguridad;
import com.esibuy.esibuy_backend.servicio.ServicioCategoria;

/**
 * Endpoint DELETE /api/admin/categorias/{id}: respuestas HTTP y mensajes que recibe el frontend.
 */
@WebMvcTest(ControladorCategoria.class)
@Import(ConfiguracionSeguridad.class)
class ControladorCategoriaTest {

    private static final String ID = "64b7f0c2a1b2c3d4e5f60718";
    private static final String RUTA = "/api/admin/categorias/" + ID;

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private ServicioCategoria servicioCategoria;

    @Test
    @WithMockUser(roles = "ADMIN")
    void eliminar_administrador_devuelve204YLlamaAlServicio() throws Exception {
        // When / Then
        mockMvc.perform(delete(RUTA))
                .andExpect(status().isNoContent());
        verify(servicioCategoria).eliminarCategoria(ID);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void eliminar_categoriaInexistente_devuelve404ConMensaje() throws Exception {
        // Given
        doThrow(new CategoriaNoEncontradaException()).when(servicioCategoria).eliminarCategoria(ID);

        // When / Then
        mockMvc.perform(delete(RUTA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.mensaje").value("La categoría no existe o ya ha sido eliminada"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void eliminar_categoriaConProductos_devuelve409ConElMotivo() throws Exception {
        // Given
        doThrow(new CategoriaConProductosException("Moda", 3)).when(servicioCategoria).eliminarCategoria(ID);

        // When / Then
        mockMvc.perform(delete(RUTA))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensaje")
                        .value("No se puede eliminar la categoría «Moda» porque tiene 3 productos asociados"));
    }

    @Test
    void eliminar_sinIniciarSesion_devuelve403YNoBorra() throws Exception {
        // Given: visitante anonimo

        // When / Then
        mockMvc.perform(delete(RUTA))
                .andExpect(status().isForbidden());
        verify(servicioCategoria, never()).eliminarCategoria(any());
    }
}