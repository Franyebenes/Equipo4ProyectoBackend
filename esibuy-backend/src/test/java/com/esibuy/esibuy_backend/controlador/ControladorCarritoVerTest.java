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

import com.esibuy.esibuy_backend.dto.CarritoDTO;
import com.esibuy.esibuy_backend.dto.LineaCarritoDTO;
import com.esibuy.esibuy_backend.seguridad.ConfiguracionSeguridad;
import com.esibuy.esibuy_backend.servicio.ServicioCarrito;

/** HU-22.1 Ver carrito. Endpoint GET /api/carrito: solo clientes y premium, siempre el carrito de la sesion. */
@WebMvcTest(ControladorCarrito.class)
@Import(ConfiguracionSeguridad.class)
class ControladorCarritoVerTest {

    private static final String ID_CLIENTE = "64b7f0c2a1b2c3d4e5f60001";
    private static final String ID_PRODUCTO = "64b7f0c2a1b2c3d4e5f60101";
    private static final String RUTA = "/api/carrito";
    private static final CarritoDTO CARRITO = new CarritoDTO(
            List.of(new LineaCarritoDTO(ID_PRODUCTO, "Teclado", null, 18.0, 20.0, 2, 36.0, true)), 2, 36.0);

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private ServicioCarrito servicioCarrito;

    @Test
    @WithMockUser(username = ID_CLIENTE, roles = "CLIENTE")
    void verCarrito_cliente_devuelve200ConSuCarritoSinDescuentoPremium() throws Exception {
        when(servicioCarrito.obtener(ID_CLIENTE, false)).thenReturn(CARRITO);

        mockMvc.perform(get(RUTA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lineas[0].nombre").value("Teclado"))
                .andExpect(jsonPath("$.lineas[0].subtotal").value(36.0))
                .andExpect(jsonPath("$.total").value(36.0));
        verify(servicioCarrito).obtener(ID_CLIENTE, false);
    }

    @Test
    @WithMockUser(username = ID_CLIENTE, roles = "PREMIUM")
    void verCarrito_premium_pideElCarritoConDescuentoPremium() throws Exception {
        when(servicioCarrito.obtener(ID_CLIENTE, true)).thenReturn(CARRITO);

        mockMvc.perform(get(RUTA)).andExpect(status().isOk());
        verify(servicioCarrito).obtener(ID_CLIENTE, true);
    }

    @Test
    @WithMockUser(roles = "VENDEDOR")
    void verCarrito_vendedor_devuelve403() throws Exception {
        mockMvc.perform(get(RUTA)).andExpect(status().isForbidden());
        verify(servicioCarrito, never()).obtener(anyString(), anyBoolean());
    }

    @Test
    void verCarrito_sinIniciarSesion_devuelve401() throws Exception {
        mockMvc.perform(get(RUTA)).andExpect(status().isUnauthorized());
        verify(servicioCarrito, never()).obtener(anyString(), anyBoolean());
    }
}