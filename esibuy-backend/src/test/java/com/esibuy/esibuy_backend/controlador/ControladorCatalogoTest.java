package com.esibuy.esibuy_backend.controlador;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
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
import com.esibuy.esibuy_backend.dto.PaginaDTO;
import com.esibuy.esibuy_backend.dto.ProductoClienteDTO;
import com.esibuy.esibuy_backend.seguridad.ConfiguracionSeguridad;
import com.esibuy.esibuy_backend.servicio.FiltroCatalogoCliente;
import com.esibuy.esibuy_backend.servicio.ServicioCatalogo;

/** HU-19 Buscar productos. Endpoint GET /api/catalogo: solo clientes y premium, con la sesion iniciada. */
@WebMvcTest(ControladorCatalogo.class)
@Import(ConfiguracionSeguridad.class)
class ControladorCatalogoTest {

    private static final String ID_CLIENTE = "64b7f0c2a1b2c3d4e5f60001";
    private static final String RUTA = "/api/catalogo";
    private static final FiltroCatalogoCliente BUSQUEDA_TAZA =
            new FiltroCatalogoCliente("taza", null, null, null, null, false);
    private static final PaginaDTO<ProductoClienteDTO> PAGINA = new PaginaDTO<>(List.of(new ProductoClienteDTO(
            "64b7f0c2a1b2c3d4e5f60101", "Taza", null, List.of("Cocina"), 4.0, 3.6, false,
            Disponibilidad.POCO_STOCK, 4.5, 2)), 0, 12, 1, 1);

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private ServicioCatalogo servicioCatalogo;

    @Test
    @WithMockUser(username = ID_CLIENTE, roles = "CLIENTE")
    void buscar_clienteSinParametros_devuelveLaPrimeraPaginaSinElStock() throws Exception {
        when(servicioCatalogo.buscar(FiltroCatalogoCliente.sinFiltros(), 0, false)).thenReturn(PAGINA);

        mockMvc.perform(get(RUTA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contenido[0].nombre").value("Taza"))
                .andExpect(jsonPath("$.contenido[0].categorias[0]").value("Cocina"))
                .andExpect(jsonPath("$.contenido[0].precioFinal").value(3.6))
                .andExpect(jsonPath("$.contenido[0].disponibilidad").value("POCO_STOCK"))
                .andExpect(jsonPath("$.contenido[0].stock").doesNotExist())
                .andExpect(jsonPath("$.totalPaginas").value(1));
    }

    @Test
    @WithMockUser(username = ID_CLIENTE, roles = "PREMIUM")
    void buscar_premiumConBusquedaYPagina_pideEsaPaginaConSuDescuento() throws Exception {
        when(servicioCatalogo.buscar(BUSQUEDA_TAZA, 1, true)).thenReturn(PAGINA);

        mockMvc.perform(get(RUTA).param("busqueda", "taza").param("pagina", "1")).andExpect(status().isOk());
        verify(servicioCatalogo).buscar(BUSQUEDA_TAZA, 1, true);
    }

    @Test
    @WithMockUser(roles = "VENDEDOR")
    void buscar_vendedor_devuelve403() throws Exception {
        mockMvc.perform(get(RUTA)).andExpect(status().isForbidden());
        verify(servicioCatalogo, never()).buscar(any(), anyInt(), anyBoolean());
    }

    @Test
    void buscar_sinIniciarSesion_devuelve401() throws Exception {
        mockMvc.perform(get(RUTA)).andExpect(status().isUnauthorized());
        verify(servicioCatalogo, never()).buscar(any(), anyInt(), anyBoolean());
    }
}