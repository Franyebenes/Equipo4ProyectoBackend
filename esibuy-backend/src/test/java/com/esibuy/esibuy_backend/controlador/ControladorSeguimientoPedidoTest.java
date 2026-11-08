package com.esibuy.esibuy_backend.controlador;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.esibuy.esibuy_backend.dto.PasoSeguimientoDTO;
import com.esibuy.esibuy_backend.dto.SeguimientoPedidoDTO;
import com.esibuy.esibuy_backend.excepcion.PedidoNoEncontradoException;
import com.esibuy.esibuy_backend.modelo.EstadoPedido;
import com.esibuy.esibuy_backend.seguridad.ConfiguracionSeguridad;
import com.esibuy.esibuy_backend.servicio.ServicioSeguimientoPedido;

/**
 * Estado del envio. GET /api/pedidos/{id}/seguimiento: solo clientes (estandar y premium), siempre con el cliente de
 * la sesion, y las respuestas y mensajes que recibe el frontend.
 */
@WebMvcTest(ControladorSeguimientoPedido.class)
@Import(ConfiguracionSeguridad.class)
class ControladorSeguimientoPedidoTest {

    private static final String ID_CLIENTE = "64c000000000000000000011";
    private static final String ID_PEDIDO = "64c000000000000000000101";
    private static final String RUTA = "/api/pedidos/" + ID_PEDIDO + "/seguimiento";
    private static final Instant FECHA = Instant.parse("2026-11-02T09:30:00Z");
    private static final Instant ENVIADO = Instant.parse("2026-11-03T10:00:00Z");
    private static final SeguimientoPedidoDTO SEGUIMIENTO = new SeguimientoPedidoDTO(ID_PEDIDO, EstadoPedido.ENVIADO,
            FECHA, ENVIADO, LocalDate.of(2026, 11, 7), null, true, List.of(
                    new PasoSeguimientoDTO(EstadoPedido.PAGADO, true, false, FECHA),
                    new PasoSeguimientoDTO(EstadoPedido.EN_PREPARACION, true, false, null),
                    new PasoSeguimientoDTO(EstadoPedido.ENVIADO, true, true, ENVIADO),
                    new PasoSeguimientoDTO(EstadoPedido.EN_REPARTO, false, false, null),
                    new PasoSeguimientoDTO(EstadoPedido.ENTREGADO, false, false, null)));

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private ServicioSeguimientoPedido servicioSeguimientoPedido;

    @Test
    @WithMockUser(username = ID_CLIENTE, roles = "CLIENTE")
    void cliente_devuelve200ConElSeguimientoYUsaElClienteDeLaSesion() throws Exception {
        when(servicioSeguimientoPedido.consultar(ID_CLIENTE, ID_PEDIDO)).thenReturn(SEGUIMIENTO);

        mockMvc.perform(get(RUTA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("ENVIADO"))
                .andExpect(jsonPath("$.fechaActualizacion").exists())
                .andExpect(jsonPath("$.fechaEntregaEstimada").value("2026-11-07"))
                .andExpect(jsonPath("$.disponible").value(true))
                .andExpect(jsonPath("$.pasos.length()").value(5))
                .andExpect(jsonPath("$.pasos[2].estado").value("ENVIADO"))
                .andExpect(jsonPath("$.pasos[2].actual").value(true));
        verify(servicioSeguimientoPedido).consultar(ID_CLIENTE, ID_PEDIDO);
    }

    @Test
    @WithMockUser(username = ID_CLIENTE, roles = "PREMIUM")
    void clientePremium_tambienPuedeConsultarlo() throws Exception {
        when(servicioSeguimientoPedido.consultar(ID_CLIENTE, ID_PEDIDO)).thenReturn(SEGUIMIENTO);

        mockMvc.perform(get(RUTA)).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = ID_CLIENTE, roles = "CLIENTE")
    void pedidoAjenoOInexistente_devuelve404ConMensaje() throws Exception {
        when(servicioSeguimientoPedido.consultar(ID_CLIENTE, ID_PEDIDO)).thenThrow(new PedidoNoEncontradoException());

        mockMvc.perform(get(RUTA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.mensaje").value("El pedido no existe"));
    }

    @Test
    @WithMockUser(roles = "VENDEDOR")
    void vendedor_devuelve403YNoConsultaNada() throws Exception {
        mockMvc.perform(get(RUTA)).andExpect(status().isForbidden());
        verify(servicioSeguimientoPedido, never()).consultar(anyString(), anyString());
    }

    @Test
    void sinIniciarSesion_devuelve401() throws Exception {
        mockMvc.perform(get(RUTA)).andExpect(status().isUnauthorized());
        verify(servicioSeguimientoPedido, never()).consultar(anyString(), anyString());
    }
}
