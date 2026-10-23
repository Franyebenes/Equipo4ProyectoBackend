package com.esibuy.esibuy_backend.controlador;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.esibuy.esibuy_backend.dto.ProductoCatalogoDTO;
import com.esibuy.esibuy_backend.excepcion.ProductoInvalidoException;
import com.esibuy.esibuy_backend.excepcion.ProductoNoEncontradoException;
import com.esibuy.esibuy_backend.seguridad.ConfiguracionSeguridad;
import com.esibuy.esibuy_backend.servicio.ServicioProducto;

/**
 * HU Activar/Desactivar producto. Endpoint PATCH /api/vendedor/productos/{id}/estado: solo vendedores, con token
 * CSRF y siempre sobre el catalogo del vendedor de la sesion; respuestas y mensajes que recibe el frontend.
 */
@WebMvcTest(ControladorProducto.class)
@Import(ConfiguracionSeguridad.class)
class ControladorProductoEstadoTest {

    // El principal de la sesion es el id del usuario (EstablecedorSesion)
    private static final String ID_VENDEDOR = "64b7f0c2a1b2c3d4e5f60001";
    private static final String ID_PRODUCTO = "64b7f0c2a1b2c3d4e5f60101";
    private static final String RUTA = "/api/vendedor/productos/" + ID_PRODUCTO + "/estado";
    private static final String DESACTIVAR = "{\"activo\":false}";
    private static final ProductoCatalogoDTO LAMPARA_INACTIVA = new ProductoCatalogoDTO(ID_PRODUCTO, "Lámpara",
            "Lámpara de sobremesa.", null, List.of("Hogar"), 80.0, null, null, 4, false, false);

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private ServicioProducto servicioProducto;

    @Test
    @WithMockUser(username = ID_VENDEDOR, roles = "VENDEDOR")
    void cambiarEstado_vendedor_devuelve200ConElEstadoNuevoYUsaElVendedorDeLaSesion() throws Exception {
        // Given
        when(servicioProducto.cambiarEstado(ID_VENDEDOR, ID_PRODUCTO, false)).thenReturn(LAMPARA_INACTIVA);

        // When / Then
        mockMvc.perform(patch(RUTA).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(DESACTIVAR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ID_PRODUCTO))
                .andExpect(jsonPath("$.visible").value(false));
        verify(servicioProducto).cambiarEstado(ID_VENDEDOR, ID_PRODUCTO, false);
    }

    @Test
    @WithMockUser(username = ID_VENDEDOR, roles = "VENDEDOR")
    void cambiarEstado_productoAjenoOInexistente_devuelve404ConMensaje() throws Exception {
        // Given
        when(servicioProducto.cambiarEstado(ID_VENDEDOR, ID_PRODUCTO, false))
                .thenThrow(new ProductoNoEncontradoException());

        // When / Then
        mockMvc.perform(patch(RUTA).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(DESACTIVAR))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.mensaje").value("El producto no existe en tu catálogo"));
    }

    @Test
    @WithMockUser(username = ID_VENDEDOR, roles = "VENDEDOR")
    void cambiarEstado_sinIndicarElEstado_devuelve400ConElMensajeDelServicio() throws Exception {
        // Given
        when(servicioProducto.cambiarEstado(ID_VENDEDOR, ID_PRODUCTO, null))
                .thenThrow(new ProductoInvalidoException("Indica si el producto queda activo o inactivo"));

        // When / Then
        mockMvc.perform(patch(RUTA).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value("Indica si el producto queda activo o inactivo"));
    }

    @Test
    @WithMockUser(username = ID_VENDEDOR, roles = "VENDEDOR")
    void cambiarEstado_sinTokenCsrf_devuelve403YNoCambiaNada() throws Exception {
        // When / Then
        mockMvc.perform(patch(RUTA).contentType(MediaType.APPLICATION_JSON).content(DESACTIVAR))
                .andExpect(status().isForbidden());
        verify(servicioProducto, never()).cambiarEstado(anyString(), anyString(), any());
    }

    @Test
    @WithMockUser(roles = "CLIENTE")
    void cambiarEstado_cliente_devuelve403YNoCambiaNada() throws Exception {
        // When / Then
        mockMvc.perform(patch(RUTA).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(DESACTIVAR))
                .andExpect(status().isForbidden());
        verify(servicioProducto, never()).cambiarEstado(anyString(), anyString(), any());
    }

    @Test
    void cambiarEstado_sinIniciarSesion_devuelve401YNoCambiaNada() throws Exception {
        // When / Then
        mockMvc.perform(patch(RUTA).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(DESACTIVAR))
                .andExpect(status().isUnauthorized());
        verify(servicioProducto, never()).cambiarEstado(anyString(), anyString(), any());
    }
}
