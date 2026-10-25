package com.esibuy.esibuy_backend.controlador;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
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

import com.esibuy.esibuy_backend.dto.Disponibilidad;
import com.esibuy.esibuy_backend.dto.ProductoDetalleDTO;
import com.esibuy.esibuy_backend.excepcion.ProductoNoDisponibleException;
import com.esibuy.esibuy_backend.seguridad.ConfiguracionSeguridad;
import com.esibuy.esibuy_backend.servicio.ServicioCatalogo;

/** HU-21 Ver detalle del producto. Endpoint GET /api/catalogo/{id}: solo clientes y premium. */
@WebMvcTest(ControladorCatalogo.class)
@Import(ConfiguracionSeguridad.class)
class ControladorCatalogoDetalleTest {

    private static final String ID_CLIENTE = "64b7f0c2a1b2c3d4e5f60001";
    private static final String ID_PRODUCTO = "64b7f0c2a1b2c3d4e5f60101";
    private static final String RUTA = "/api/catalogo/" + ID_PRODUCTO;
    private static final ProductoDetalleDTO FICHA = new ProductoDetalleDTO(ID_PRODUCTO, "Taza", "Taza de cerámica.",
            null, List.of("Cocina"), 4.0, 3.6, 10.0, null, false, Disponibilidad.AGOTADO, null, 0);

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private ServicioCatalogo servicioCatalogo;

    @Test
    @WithMockUser(username = ID_CLIENTE, roles = "CLIENTE")
    void verDetalle_cliente_devuelve200ConLaFichaSinElStock() throws Exception {
        when(servicioCatalogo.obtener(ID_PRODUCTO, false)).thenReturn(FICHA);

        mockMvc.perform(get(RUTA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre").value("Taza"))
                .andExpect(jsonPath("$.descripcion").value("Taza de cerámica."))
                .andExpect(jsonPath("$.categorias[0]").value("Cocina"))
                .andExpect(jsonPath("$.precioFinal").value(3.6))
                .andExpect(jsonPath("$.disponibilidad").value("AGOTADO"))
                .andExpect(jsonPath("$.numeroValoraciones").value(0))
                .andExpect(jsonPath("$.stock").doesNotExist());
    }

    @Test
    @WithMockUser(username = ID_CLIENTE, roles = "PREMIUM")
    void verDetalle_premium_pideLaFichaConSuDescuento() throws Exception {
        when(servicioCatalogo.obtener(ID_PRODUCTO, true)).thenReturn(FICHA);

        mockMvc.perform(get(RUTA)).andExpect(status().isOk());
        verify(servicioCatalogo).obtener(ID_PRODUCTO, true);
    }

    @Test
    @WithMockUser(username = ID_CLIENTE, roles = "CLIENTE")
    void verDetalle_productoInactivoOInexistente_devuelve404ConMensaje() throws Exception {
        when(servicioCatalogo.obtener(ID_PRODUCTO, false)).thenThrow(new ProductoNoDisponibleException());

        mockMvc.perform(get(RUTA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.mensaje").exists());
    }

    @Test
    @WithMockUser(roles = "VENDEDOR")
    void verDetalle_vendedor_devuelve403() throws Exception {
        mockMvc.perform(get(RUTA)).andExpect(status().isForbidden());
        verify(servicioCatalogo, never()).obtener(anyString(), anyBoolean());
    }

    @Test
    void verDetalle_sinIniciarSesion_devuelve401() throws Exception {
        mockMvc.perform(get(RUTA)).andExpect(status().isUnauthorized());
        verify(servicioCatalogo, never()).obtener(anyString(), anyBoolean());
    }
}
