package com.esibuy.esibuy_backend.controlador;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
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

import com.esibuy.esibuy_backend.excepcion.ProductoNoEncontradoException;
import com.esibuy.esibuy_backend.seguridad.ConfiguracionSeguridad;
import com.esibuy.esibuy_backend.servicio.ServicioProducto;

/**
 * HU Eliminar producto. Endpoint DELETE /api/vendedor/productos/{id}: solo vendedores, con token CSRF y siempre
 * sobre el catalogo del vendedor de la sesion; respuestas y mensajes que recibe el frontend.
 */
@WebMvcTest(ControladorProducto.class)
@Import(ConfiguracionSeguridad.class)
class ControladorProductoEliminarTest {

    // El principal de la sesion es el id del usuario (EstablecedorSesion)
    private static final String ID_VENDEDOR = "64b7f0c2a1b2c3d4e5f60001";
    private static final String ID_PRODUCTO = "64b7f0c2a1b2c3d4e5f60101";
    private static final String RUTA = "/api/vendedor/productos/" + ID_PRODUCTO;

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private ServicioProducto servicioProducto;

    @Test
    @WithMockUser(username = ID_VENDEDOR, roles = "VENDEDOR")
    void eliminar_vendedor_devuelve204YUsaElVendedorDeLaSesion() throws Exception {
        // When / Then
        mockMvc.perform(delete(RUTA).with(csrf()))
                .andExpect(status().isNoContent());
        verify(servicioProducto).eliminarProducto(ID_VENDEDOR, ID_PRODUCTO);
    }

    @Test
    @WithMockUser(username = ID_VENDEDOR, roles = "VENDEDOR")
    void eliminar_productoAjenoOInexistente_devuelve404ConMensaje() throws Exception {
        // Given
        doThrow(new ProductoNoEncontradoException()).when(servicioProducto).eliminarProducto(ID_VENDEDOR, ID_PRODUCTO);

        // When / Then
        mockMvc.perform(delete(RUTA).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.mensaje").value("El producto no existe en tu catálogo"));
    }

    @Test
    @WithMockUser(username = ID_VENDEDOR, roles = "VENDEDOR")
    void eliminar_sinTokenCsrf_devuelve403YNoBorra() throws Exception {
        // When / Then
        mockMvc.perform(delete(RUTA))
                .andExpect(status().isForbidden());
        verify(servicioProducto, never()).eliminarProducto(anyString(), anyString());
    }

    @Test
    @WithMockUser(roles = "CLIENTE")
    void eliminar_cliente_devuelve403YNoBorra() throws Exception {
        // When / Then
        mockMvc.perform(delete(RUTA).with(csrf()))
                .andExpect(status().isForbidden());
        verify(servicioProducto, never()).eliminarProducto(anyString(), anyString());
    }

    @Test
    void eliminar_sinIniciarSesion_devuelve401YNoBorra() throws Exception {
        // When / Then
        mockMvc.perform(delete(RUTA).with(csrf()))
                .andExpect(status().isUnauthorized());
        verify(servicioProducto, never()).eliminarProducto(anyString(), anyString());
    }
}
