package com.esibuy.esibuy_backend.controlador;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

import com.esibuy.esibuy_backend.dto.CarritoDTO;
import com.esibuy.esibuy_backend.dto.LineaCarritoDTO;
import com.esibuy.esibuy_backend.excepcion.ProductoNoEnCarritoException;
import com.esibuy.esibuy_backend.seguridad.ConfiguracionSeguridad;
import com.esibuy.esibuy_backend.servicio.ServicioCarrito;

/**
 * HU-22.3 y HU-22.4. PUT y DELETE /api/carrito/{idProducto}: solo clientes y premium, con token CSRF; devuelven el
 * carrito actualizado.
 */
@WebMvcTest(ControladorCarrito.class)
@Import(ConfiguracionSeguridad.class)
class ControladorCarritoModificarTest {

    private static final String ID_CLIENTE = "64b7f0c2a1b2c3d4e5f60001";
    private static final String ID_PRODUCTO = "64b7f0c2a1b2c3d4e5f60101";
    private static final String RUTA = "/api/carrito/" + ID_PRODUCTO;
    private static final String TRES_UNIDADES = "{\"cantidad\":3}";
    private static final CarritoDTO CARRITO = new CarritoDTO(
            List.of(new LineaCarritoDTO(ID_PRODUCTO, "Teclado", null, 18.0, 20.0, 3, 54.0, true)), 3, 54.0);

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private ServicioCarrito servicioCarrito;

    // ---------- PUT: modificar cantidad ----------

    @Test
    @WithMockUser(username = ID_CLIENTE, roles = "CLIENTE")
    void cambiarCantidad_cliente_devuelve200ConElCarritoActualizado() throws Exception {
        when(servicioCarrito.obtener(ID_CLIENTE, false)).thenReturn(CARRITO);

        mockMvc.perform(put(RUTA).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(TRES_UNIDADES))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lineas[0].cantidad").value(3))
                .andExpect(jsonPath("$.total").value(54.0));
        verify(servicioCarrito).cambiarCantidad(ID_CLIENTE, ID_PRODUCTO, 3, false);
    }

    @Test
    @WithMockUser(username = ID_CLIENTE, roles = "CLIENTE")
    void cambiarCantidad_productoQueNoEstaEnElCarrito_devuelve404ConMensaje() throws Exception {
        doThrow(new ProductoNoEnCarritoException()).when(servicioCarrito).cambiarCantidad(ID_CLIENTE, ID_PRODUCTO, 3, false);

        mockMvc.perform(put(RUTA).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(TRES_UNIDADES))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.mensaje").value("El producto no esta en tu carrito"));
    }

    @Test
    @WithMockUser(username = ID_CLIENTE, roles = "CLIENTE")
    void cambiarCantidad_sinTokenCsrf_devuelve403() throws Exception {
        mockMvc.perform(put(RUTA).contentType(MediaType.APPLICATION_JSON).content(TRES_UNIDADES))
                .andExpect(status().isForbidden());
        verify(servicioCarrito, never()).cambiarCantidad(anyString(), anyString(), anyInt(), anyBoolean());
    }

    @Test
    @WithMockUser(roles = "VENDEDOR")
    void cambiarCantidad_vendedor_devuelve403() throws Exception {
        mockMvc.perform(put(RUTA).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(TRES_UNIDADES))
                .andExpect(status().isForbidden());
        verify(servicioCarrito, never()).cambiarCantidad(anyString(), anyString(), anyInt(), anyBoolean());
    }

    @Test
    void cambiarCantidad_sinIniciarSesion_devuelve401() throws Exception {
        mockMvc.perform(put(RUTA).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(TRES_UNIDADES))
                .andExpect(status().isUnauthorized());
        verify(servicioCarrito, never()).cambiarCantidad(anyString(), anyString(), anyInt(), anyBoolean());
    }

    // ---------- DELETE: eliminar ----------

    @Test
    @WithMockUser(username = ID_CLIENTE, roles = "PREMIUM")
    void eliminar_premium_devuelve200ConElCarritoActualizado() throws Exception {
        when(servicioCarrito.obtener(ID_CLIENTE, true)).thenReturn(CarritoDTO.vacio());

        mockMvc.perform(delete(RUTA).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lineas").isEmpty())
                .andExpect(jsonPath("$.total").value(0.0));
        verify(servicioCarrito).eliminar(ID_CLIENTE, ID_PRODUCTO);
    }

    @Test
    @WithMockUser(username = ID_CLIENTE, roles = "CLIENTE")
    void eliminar_sinTokenCsrf_devuelve403() throws Exception {
        mockMvc.perform(delete(RUTA)).andExpect(status().isForbidden());
        verify(servicioCarrito, never()).eliminar(anyString(), anyString());
    }

    @Test
    void eliminar_sinIniciarSesion_devuelve401() throws Exception {
        mockMvc.perform(delete(RUTA).with(csrf())).andExpect(status().isUnauthorized());
        verify(servicioCarrito, never()).eliminar(anyString(), anyString());
    }
}