package com.esibuy.esibuy_backend.controlador;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.esibuy.esibuy_backend.excepcion.CantidadNoValidaException;
import com.esibuy.esibuy_backend.excepcion.ProductoNoDisponibleException;
import com.esibuy.esibuy_backend.excepcion.StockInsuficienteException;
import com.esibuy.esibuy_backend.seguridad.ConfiguracionSeguridad;
import com.esibuy.esibuy_backend.servicio.ServicioCarrito;

/**
 * HU-22.2 Anadir al carrito. Endpoint POST /api/carrito: solo clientes y premium, con token CSRF y siempre sobre el
 * carrito del cliente de la sesion.
 */
@WebMvcTest(ControladorCarrito.class)
@Import(ConfiguracionSeguridad.class)
class ControladorCarritoTest {

    // El principal de la sesion es el id del usuario
    private static final String ID_CLIENTE = "64b7f0c2a1b2c3d4e5f60001";
    private static final String ID_PRODUCTO = "64b7f0c2a1b2c3d4e5f60101";
    private static final String RUTA = "/api/carrito";
    private static final String DOS_UNIDADES = "{\"idProducto\":\"" + ID_PRODUCTO + "\",\"cantidad\":2}";

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private ServicioCarrito servicioCarrito;

    // P11: el cliente sale de la sesion, no de la peticion
    @Test
    @WithMockUser(username = ID_CLIENTE, roles = "CLIENTE")
    void anadir_cliente_devuelve204YUsaElClienteDeLaSesion() throws Exception {
        mockMvc.perform(post(RUTA).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(DOS_UNIDADES))
                .andExpect(status().isNoContent());
        verify(servicioCarrito).anadir(ID_CLIENTE, ID_PRODUCTO, 2);
    }

    // P12
    @Test
    @WithMockUser(username = ID_CLIENTE, roles = "PREMIUM")
    void anadir_premium_devuelve204() throws Exception {
        mockMvc.perform(post(RUTA).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(DOS_UNIDADES))
                .andExpect(status().isNoContent());
        verify(servicioCarrito).anadir(ID_CLIENTE, ID_PRODUCTO, 2);
    }

    // P10
    @Test
    @WithMockUser(roles = "VENDEDOR")
    void anadir_vendedor_devuelve403YNoAnadeNada() throws Exception {
        mockMvc.perform(post(RUTA).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(DOS_UNIDADES))
                .andExpect(status().isForbidden());
        verify(servicioCarrito, never()).anadir(anyString(), anyString(), anyInt());
    }

    // P10
    @Test
    @WithMockUser(roles = "ADMIN")
    void anadir_administrador_devuelve403YNoAnadeNada() throws Exception {
        mockMvc.perform(post(RUTA).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(DOS_UNIDADES))
                .andExpect(status().isForbidden());
        verify(servicioCarrito, never()).anadir(anyString(), anyString(), anyInt());
    }

    // P9
    @Test
    void anadir_sinIniciarSesion_devuelve401YNoAnadeNada() throws Exception {
        mockMvc.perform(post(RUTA).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(DOS_UNIDADES))
                .andExpect(status().isUnauthorized());
        verify(servicioCarrito, never()).anadir(anyString(), anyString(), anyInt());
    }

    @Test
    @WithMockUser(username = ID_CLIENTE, roles = "CLIENTE")
    void anadir_sinTokenCsrf_devuelve403YNoAnadeNada() throws Exception {
        mockMvc.perform(post(RUTA).contentType(MediaType.APPLICATION_JSON).content(DOS_UNIDADES))
                .andExpect(status().isForbidden());
        verify(servicioCarrito, never()).anadir(anyString(), anyString(), anyInt());
    }

    @Test
    @WithMockUser(username = ID_CLIENTE, roles = "CLIENTE")
    void anadir_sinStock_devuelve409ConMensaje() throws Exception {
        doThrow(new StockInsuficienteException()).when(servicioCarrito).anadir(ID_CLIENTE, ID_PRODUCTO, 2);

        mockMvc.perform(post(RUTA).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(DOS_UNIDADES))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensaje").value("No hay stock suficiente de este producto"));
    }

    @Test
    @WithMockUser(username = ID_CLIENTE, roles = "CLIENTE")
    void anadir_productoNoDisponible_devuelve404ConMensaje() throws Exception {
        doThrow(new ProductoNoDisponibleException()).when(servicioCarrito).anadir(ID_CLIENTE, ID_PRODUCTO, 2);

        mockMvc.perform(post(RUTA).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(DOS_UNIDADES))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.mensaje").value("El producto no existe o no esta disponible"));
    }

    @Test
    @WithMockUser(username = ID_CLIENTE, roles = "CLIENTE")
    void anadir_cantidadNoValida_devuelve400ConMensaje() throws Exception {
        doThrow(new CantidadNoValidaException()).when(servicioCarrito).anadir(ID_CLIENTE, ID_PRODUCTO, 2);

        mockMvc.perform(post(RUTA).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(DOS_UNIDADES))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value("La cantidad debe ser al menos 1"));
    }
}