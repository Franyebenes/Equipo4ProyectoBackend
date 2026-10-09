package com.esibuy.esibuy_backend.controlador;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

import com.esibuy.esibuy_backend.dto.ProductoCatalogoDTO;
import com.esibuy.esibuy_backend.excepcion.ProductoNoEncontradoException;
import com.esibuy.esibuy_backend.seguridad.ConfiguracionSeguridad;
import com.esibuy.esibuy_backend.servicio.ServicioProducto;

/**
 * HU-15.2 Endpoints GET /api/vendedor/productos y /{id}: solo para vendedores, siempre con el vendedor de la sesion,
 * y las respuestas y mensajes que recibe el frontend.
 */
@WebMvcTest(ControladorProducto.class)
@Import(ConfiguracionSeguridad.class)
class ControladorProductoTest {

    // El principal de la sesion es el id del usuario (EstablecedorSesion)
    private static final String ID_VENDEDOR = "64b7f0c2a1b2c3d4e5f60001";
    private static final String ID_PRODUCTO = "64b7f0c2a1b2c3d4e5f60101";
    private static final String RUTA = "/api/vendedor/productos";
    private static final ProductoCatalogoDTO BOLSO = new ProductoCatalogoDTO(ID_PRODUCTO, "Bolso de piel",
            "Bolso hecho a mano.", null, List.of("Moda"), 49.9, 10.0, null, 3, true);

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private ServicioProducto servicioProducto;

    @Test
    @WithMockUser(username = ID_VENDEDOR, roles = "VENDEDOR")
    void listar_vendedor_devuelve200ConLosProductosDeSuSesion() throws Exception {
        // Given
        when(servicioProducto.listarProductosDelVendedor(ID_VENDEDOR)).thenReturn(List.of(BOLSO));

        // When / Then
        mockMvc.perform(get(RUTA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(ID_PRODUCTO))
                .andExpect(jsonPath("$[0].nombre").value("Bolso de piel"))
                .andExpect(jsonPath("$[0].categorias[0]").value("Moda"))
                .andExpect(jsonPath("$[0].precio").value(49.9))
                .andExpect(jsonPath("$[0].stock").value(3))
                .andExpect(jsonPath("$[0].visible").value(true));
        verify(servicioProducto).listarProductosDelVendedor(ID_VENDEDOR);
    }

    @Test
    @WithMockUser(username = ID_VENDEDOR, roles = "VENDEDOR")
    void listar_vendedorSinProductos_devuelve200ConListaVacia() throws Exception {
        // Given
        when(servicioProducto.listarProductosDelVendedor(ID_VENDEDOR)).thenReturn(List.of());

        // When / Then
        mockMvc.perform(get(RUTA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @WithMockUser(roles = "CLIENTE")
    void listar_cliente_devuelve403YNoConsulta() throws Exception {
        // When / Then
        mockMvc.perform(get(RUTA))
                .andExpect(status().isForbidden());
        verify(servicioProducto, never()).listarProductosDelVendedor(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void listar_administrador_devuelve403YNoConsulta() throws Exception {
        // When / Then: el administrador supervisa productos en su propia HU (modo lectura), no aqui
        mockMvc.perform(get(RUTA))
                .andExpect(status().isForbidden());
        verify(servicioProducto, never()).listarProductosDelVendedor(any());
    }

    @Test
    void listar_sinIniciarSesion_devuelve401YNoConsulta() throws Exception {
        // Given: visitante anonimo

        // When / Then
        mockMvc.perform(get(RUTA))
                .andExpect(status().isUnauthorized());
        verify(servicioProducto, never()).listarProductosDelVendedor(any());
    }

    @Test
    @WithMockUser(username = ID_VENDEDOR, roles = "VENDEDOR")
    void detalle_productoPropio_devuelve200ConSusDatos() throws Exception {
        // Given
        when(servicioProducto.obtenerProductoDelVendedor(ID_VENDEDOR, ID_PRODUCTO)).thenReturn(BOLSO);

        // When / Then
        mockMvc.perform(get(RUTA + "/" + ID_PRODUCTO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre").value("Bolso de piel"))
                .andExpect(jsonPath("$.descripcion").value("Bolso hecho a mano."))
                .andExpect(jsonPath("$.descuento").value(10.0));
    }

    @Test
    @WithMockUser(username = ID_VENDEDOR, roles = "VENDEDOR")
    void detalle_productoAjenoOInexistente_devuelve404ConMensaje() throws Exception {
        // Given
        when(servicioProducto.obtenerProductoDelVendedor(ID_VENDEDOR, ID_PRODUCTO))
                .thenThrow(new ProductoNoEncontradoException());

        // When / Then
        mockMvc.perform(get(RUTA + "/" + ID_PRODUCTO))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.mensaje").value("El producto no existe en tu catálogo"));
    }
}
