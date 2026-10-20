package com.esibuy.esibuy_backend.controlador;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

import com.esibuy.esibuy_backend.dto.MetodoPagoAltaDTO;
import com.esibuy.esibuy_backend.dto.MetodoPagoDTO;
import com.esibuy.esibuy_backend.excepcion.MetodoPagoInvalidoException;
import com.esibuy.esibuy_backend.excepcion.MetodoPagoNoEncontradoException;
import com.esibuy.esibuy_backend.seguridad.ConfiguracionSeguridad;
import com.esibuy.esibuy_backend.servicio.ServicioMetodoPago;

/** HU-24.x. /api/metodos-pago: solo clientes y premium, siempre los del cliente de la sesion. */
@WebMvcTest(ControladorMetodoPago.class)
@Import(ConfiguracionSeguridad.class)
class ControladorMetodoPagoTest {

    private static final String ID_CLIENTE = "64b7f0c2a1b2c3d4e5f60001";
    private static final String ID_METODO = "64b7f0c2a1b2c3d4e5f60050";
    private static final String RUTA = "/api/metodos-pago";
    private static final String TARJETA =
            "{\"titular\":\"Carlos Ruiz\",\"numeroTarjeta\":\"4111111111114242\",\"caducidad\":\"12/28\",\"cvv\":\"123\"}";
    private static final MetodoPagoDTO GUARDADO = new MetodoPagoDTO(ID_METODO, "Carlos Ruiz", "4242", "12/28");

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private ServicioMetodoPago servicio;

    // ---------- GET ----------

    @Test
    @WithMockUser(username = ID_CLIENTE, roles = "CLIENTE")
    void listar_cliente_devuelve200ConSusMetodos() throws Exception {
        when(servicio.listar(ID_CLIENTE)).thenReturn(List.of(GUARDADO));

        mockMvc.perform(get(RUTA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ultimosDigitos").value("4242"))
                .andExpect(jsonPath("$[0].titular").value("Carlos Ruiz"));
        verify(servicio).listar(ID_CLIENTE);
    }

    @Test
    @WithMockUser(roles = "VENDEDOR")
    void listar_vendedor_devuelve403() throws Exception {
        mockMvc.perform(get(RUTA)).andExpect(status().isForbidden());
        verify(servicio, never()).listar(anyString());
    }

    @Test
    void listar_sinIniciarSesion_devuelve401() throws Exception {
        mockMvc.perform(get(RUTA)).andExpect(status().isUnauthorized());
        verify(servicio, never()).listar(anyString());
    }

    // ---------- POST ----------

    @Test
    @WithMockUser(username = ID_CLIENTE, roles = "PREMIUM")
    void anadir_premium_devuelve201SinElNumeroNiElCvv() throws Exception {
        when(servicio.anadir(eq(ID_CLIENTE), any(MetodoPagoAltaDTO.class))).thenReturn(GUARDADO);

        mockMvc.perform(post(RUTA).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(TARJETA))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ultimosDigitos").value("4242"))
                .andExpect(jsonPath("$.numeroTarjeta").doesNotExist())
                .andExpect(jsonPath("$.cvv").doesNotExist());
        verify(servicio).anadir(eq(ID_CLIENTE), any(MetodoPagoAltaDTO.class));
    }

    @Test
    @WithMockUser(username = ID_CLIENTE, roles = "CLIENTE")
    void anadir_datosNoValidos_devuelve400ConMensaje() throws Exception {
        when(servicio.anadir(eq(ID_CLIENTE), any(MetodoPagoAltaDTO.class)))
                .thenThrow(new MetodoPagoInvalidoException("El CVV debe tener 3 o 4 digitos"));

        mockMvc.perform(post(RUTA).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(TARJETA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value("El CVV debe tener 3 o 4 digitos"));
    }

    @Test
    @WithMockUser(username = ID_CLIENTE, roles = "CLIENTE")
    void anadir_sinTokenCsrf_devuelve403YNoGuardaNada() throws Exception {
        mockMvc.perform(post(RUTA).contentType(MediaType.APPLICATION_JSON).content(TARJETA))
                .andExpect(status().isForbidden());
        verify(servicio, never()).anadir(anyString(), any());
    }

    // ---------- DELETE ----------

    @Test
    @WithMockUser(username = ID_CLIENTE, roles = "CLIENTE")
    void eliminar_cliente_devuelve204() throws Exception {
        mockMvc.perform(delete(RUTA + "/" + ID_METODO).with(csrf()))
                .andExpect(status().isNoContent());
        verify(servicio).eliminar(ID_CLIENTE, ID_METODO);
    }

    @Test
    @WithMockUser(username = ID_CLIENTE, roles = "CLIENTE")
    void eliminar_metodoAjenoOInexistente_devuelve404ConMensaje() throws Exception {
        doThrow(new MetodoPagoNoEncontradoException()).when(servicio).eliminar(ID_CLIENTE, ID_METODO);

        mockMvc.perform(delete(RUTA + "/" + ID_METODO).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.mensaje").value("El metodo de pago no existe"));
    }

    @Test
    void eliminar_sinIniciarSesion_devuelve401() throws Exception {
        mockMvc.perform(delete(RUTA + "/" + ID_METODO).with(csrf()))
                .andExpect(status().isUnauthorized());
        verify(servicio, never()).eliminar(anyString(), anyString());
    }
}