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
 * HU Gestion de stock. Endpoint PATCH /api/vendedor/productos/{id}/stock: solo vendedores, con token CSRF y siempre
 * sobre el catalogo del vendedor de la sesion; respuestas y mensajes que recibe el frontend.
 */
@WebMvcTest(ControladorProducto.class)
@Import(ConfiguracionSeguridad.class)
class ControladorProductoStockTest {

    // El principal de la sesion es el id del usuario (EstablecedorSesion)
    private static final String ID_VENDEDOR = "64b7f0c2a1b2c3d4e5f60001";
    private static final String ID_PRODUCTO = "64b7f0c2a1b2c3d4e5f60101";
    private static final String RUTA = "/api/vendedor/productos/" + ID_PRODUCTO + "/stock";
    private static final String DOCE_UNIDADES = "{\"stock\":12}";
    private static final ProductoCatalogoDTO LAMPARA = new ProductoCatalogoDTO(ID_PRODUCTO, "Lámpara",
            "Lámpara de sobremesa.", null, List.of("Hogar"), 80.0, null, null, 12, true);

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private ServicioProducto servicioProducto;

    @Test
    @WithMockUser(username = ID_VENDEDOR, roles = "VENDEDOR")
    void cambiarStock_vendedor_devuelve200ConLaCantidadActualizadaYUsaElVendedorDeLaSesion() throws Exception {
        // Given
        when(servicioProducto.cambiarStock(ID_VENDEDOR, ID_PRODUCTO, 12)).thenReturn(LAMPARA);

        // When / Then
        mockMvc.perform(patch(RUTA).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(DOCE_UNIDADES))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ID_PRODUCTO))
                .andExpect(jsonPath("$.stock").value(12));
        verify(servicioProducto).cambiarStock(ID_VENDEDOR, ID_PRODUCTO, 12);
    }

    @Test
    @WithMockUser(username = ID_VENDEDOR, roles = "VENDEDOR")
    void cambiarStock_negativo_devuelve400ConElMensajeDelServicio() throws Exception {
        // Given
        when(servicioProducto.cambiarStock(ID_VENDEDOR, ID_PRODUCTO, -3))
                .thenThrow(new ProductoInvalidoException("El stock debe ser un número entero no negativo"));

        // When / Then
        mockMvc.perform(patch(RUTA).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"stock\":-3}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value("El stock debe ser un número entero no negativo"));
    }

    @Test
    @WithMockUser(username = ID_VENDEDOR, roles = "VENDEDOR")
    void cambiarStock_cantidadQueNoEsUnNumero_devuelve400YNoCambiaNada() throws Exception {
        // When / Then
        mockMvc.perform(patch(RUTA).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stock\":\"muchas\"}"))
                .andExpect(status().isBadRequest());
        verify(servicioProducto, never()).cambiarStock(anyString(), anyString(), any());
    }

    @Test
    @WithMockUser(username = ID_VENDEDOR, roles = "VENDEDOR")
    void cambiarStock_productoAjenoOInexistente_devuelve404ConMensaje() throws Exception {
        // Given
        when(servicioProducto.cambiarStock(ID_VENDEDOR, ID_PRODUCTO, 12))
                .thenThrow(new ProductoNoEncontradoException());

        // When / Then
        mockMvc.perform(patch(RUTA).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(DOCE_UNIDADES))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.mensaje").value("El producto no existe en tu catálogo"));
    }

    @Test
    @WithMockUser(username = ID_VENDEDOR, roles = "VENDEDOR")
    void cambiarStock_sinTokenCsrf_devuelve403YNoCambiaNada() throws Exception {
        // When / Then
        mockMvc.perform(patch(RUTA).contentType(MediaType.APPLICATION_JSON).content(DOCE_UNIDADES))
                .andExpect(status().isForbidden());
        verify(servicioProducto, never()).cambiarStock(anyString(), anyString(), any());
    }

    @Test
    @WithMockUser(roles = "CLIENTE")
    void cambiarStock_cliente_devuelve403YNoCambiaNada() throws Exception {
        // When / Then
        mockMvc.perform(patch(RUTA).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(DOCE_UNIDADES))
                .andExpect(status().isForbidden());
        verify(servicioProducto, never()).cambiarStock(anyString(), anyString(), any());
    }

    @Test
    void cambiarStock_sinIniciarSesion_devuelve401YNoCambiaNada() throws Exception {
        // When / Then
        mockMvc.perform(patch(RUTA).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(DOCE_UNIDADES))
                .andExpect(status().isUnauthorized());
        verify(servicioProducto, never()).cambiarStock(anyString(), anyString(), any());
    }
}
