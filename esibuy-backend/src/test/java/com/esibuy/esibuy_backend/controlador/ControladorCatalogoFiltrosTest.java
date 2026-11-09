package com.esibuy.esibuy_backend.controlador;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
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

import com.esibuy.esibuy_backend.dto.PaginaDTO;
import com.esibuy.esibuy_backend.dto.ProductoClienteDTO;
import com.esibuy.esibuy_backend.excepcion.FiltroNoValidoException;
import com.esibuy.esibuy_backend.seguridad.ConfiguracionSeguridad;
import com.esibuy.esibuy_backend.servicio.FiltroCatalogoCliente;
import com.esibuy.esibuy_backend.servicio.ServicioCatalogo;

/** HU-20 Filtrar productos: los parametros de GET /api/catalogo llegan al servicio y un filtro sin sentido da 400. */
@WebMvcTest(ControladorCatalogo.class)
@Import(ConfiguracionSeguridad.class)
class ControladorCatalogoFiltrosTest {

    private static final String ID_CLIENTE = "64b7f0c2a1b2c3d4e5f60001";
    private static final String ID_COCINA = "64b7f0c2a1b2c3d4e5f60201";
    private static final String RUTA = "/api/catalogo";
    private static final PaginaDTO<ProductoClienteDTO> VACIA = new PaginaDTO<>(List.of(), 0, 12, 0, 0);

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private ServicioCatalogo servicioCatalogo;

    @Test
    @WithMockUser(username = ID_CLIENTE, roles = "CLIENTE")
    void buscar_conTodosLosFiltros_losPasaJuntosAlServicio() throws Exception {
        FiltroCatalogoCliente filtro = new FiltroCatalogoCliente("taza", ID_COCINA, 5.0, 20.0, 4, true);
        when(servicioCatalogo.buscar(filtro, 0, false)).thenReturn(VACIA);

        mockMvc.perform(get(RUTA).param("busqueda", "taza").param("idCategoria", ID_COCINA)
                        .param("precioMinimo", "5").param("precioMaximo", "20").param("valoracionMinima", "4")
                        .param("soloDisponibles", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElementos").value(0));
        verify(servicioCatalogo).buscar(filtro, 0, false);
    }

    @Test
    @WithMockUser(username = ID_CLIENTE, roles = "CLIENTE")
    void buscar_filtroNoValido_devuelve400ConElMotivo() throws Exception {
        when(servicioCatalogo.buscar(any(), anyInt(), anyBoolean()))
                .thenThrow(new FiltroNoValidoException("El precio mínimo no puede ser mayor que el máximo"));

        mockMvc.perform(get(RUTA).param("precioMinimo", "20").param("precioMaximo", "10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value("El precio mínimo no puede ser mayor que el máximo"));
    }

    @Test
    @WithMockUser(username = ID_CLIENTE, roles = "CLIENTE")
    void buscar_precioQueNoEsUnNumero_devuelve400() throws Exception {
        mockMvc.perform(get(RUTA).param("precioMinimo", "barato")).andExpect(status().isBadRequest());
    }
}
