package com.esibuy.esibuy_backend.controlador;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.esibuy.esibuy_backend.dto.DireccionEnvioDTO;
import com.esibuy.esibuy_backend.dto.PagarDTO;
import com.esibuy.esibuy_backend.dto.PedidoDTO;
import com.esibuy.esibuy_backend.dto.ResultadoPagoDTO;
import com.esibuy.esibuy_backend.excepcion.MetodoPagoInvalidoException;
import com.esibuy.esibuy_backend.excepcion.MetodoPagoNoEncontradoException;
import com.esibuy.esibuy_backend.modelo.EstadoPedido;
import com.esibuy.esibuy_backend.seguridad.ConfiguracionSeguridad;
import com.esibuy.esibuy_backend.servicio.ServicioPago;

/** HU-23. POST /api/pago: solo clientes y premium, con token CSRF y siempre con los datos del cliente de la sesion. */
@WebMvcTest(ControladorPago.class)
@Import(ConfiguracionSeguridad.class)
class ControladorPagoTest {

    private static final String ID_CLIENTE = "64b7f0c2a1b2c3d4e5f60001";
    private static final String ID_TARJETA = "64b7f0c2a1b2c3d4e5f60050";
    private static final String RUTA = "/api/pago";
    private static final String CUERPO = "{\"idMetodoPago\":\"" + ID_TARJETA + "\","
            + "\"direccionEnvio\":{\"calle\":\"Calle Mayor 1\",\"ciudad\":\"Ciudad Real\",\"codigoPostal\":\"13001\"}}";
    private static final DireccionEnvioDTO DIRECCION = new DireccionEnvioDTO("Calle Mayor 1", "Ciudad Real", "13001");
    private static final ResultadoPagoDTO RESULTADO = new ResultadoPagoDTO(
            new PedidoDTO("64b7f0c2a1b2c3d4e5f60090", EstadoPedido.PAGADO, Instant.parse("2026-10-25T12:00:00Z"),
                    52.5, DIRECCION, List.of()),
            "4242");

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private ServicioPago servicioPago;

    @Test
    @WithMockUser(username = ID_CLIENTE, roles = "CLIENTE")
    void pagar_cliente_devuelve201ConElPedidoPagado() throws Exception {
        when(servicioPago.pagar(eq(ID_CLIENTE), eq(false), any(PagarDTO.class))).thenReturn(RESULTADO);

        mockMvc.perform(post(RUTA).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(CUERPO))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pedido.estado").value("PAGADO"))
                .andExpect(jsonPath("$.pedido.precioTotal").value(52.5))
                .andExpect(jsonPath("$.ultimosDigitos").value("4242"));
        verify(servicioPago).pagar(eq(ID_CLIENTE), eq(false), any(PagarDTO.class));
    }

    @Test
    @WithMockUser(username = ID_CLIENTE, roles = "PREMIUM")
    void pagar_premium_pagaConDescuentoPremium() throws Exception {
        when(servicioPago.pagar(eq(ID_CLIENTE), eq(true), any(PagarDTO.class))).thenReturn(RESULTADO);

        mockMvc.perform(post(RUTA).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(CUERPO))
                .andExpect(status().isCreated());
        verify(servicioPago).pagar(eq(ID_CLIENTE), eq(true), any(PagarDTO.class));
    }

    @Test
    @WithMockUser(username = ID_CLIENTE, roles = "CLIENTE")
    void pagar_tarjetaAjenaOInexistente_devuelve404ConMensaje() throws Exception {
        when(servicioPago.pagar(eq(ID_CLIENTE), eq(false), any(PagarDTO.class)))
                .thenThrow(new MetodoPagoNoEncontradoException());

        mockMvc.perform(post(RUTA).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(CUERPO))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.mensaje").value("El metodo de pago no existe"));
    }

    @Test
    @WithMockUser(username = ID_CLIENTE, roles = "CLIENTE")
    void pagar_tarjetaCaducada_devuelve400ConMensaje() throws Exception {
        when(servicioPago.pagar(eq(ID_CLIENTE), eq(false), any(PagarDTO.class)))
                .thenThrow(new MetodoPagoInvalidoException("La tarjeta esta caducada. Elige otra"));

        mockMvc.perform(post(RUTA).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(CUERPO))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value("La tarjeta esta caducada. Elige otra"));
    }

    @Test
    @WithMockUser(username = ID_CLIENTE, roles = "CLIENTE")
    void pagar_sinTokenCsrf_devuelve403YNoPaga() throws Exception {
        mockMvc.perform(post(RUTA).contentType(MediaType.APPLICATION_JSON).content(CUERPO))
                .andExpect(status().isForbidden());
        verify(servicioPago, never()).pagar(anyString(), anyBoolean(), any());
    }

    @Test
    @WithMockUser(roles = "VENDEDOR")
    void pagar_vendedor_devuelve403YNoPaga() throws Exception {
        mockMvc.perform(post(RUTA).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(CUERPO))
                .andExpect(status().isForbidden());
        verify(servicioPago, never()).pagar(anyString(), anyBoolean(), any());
    }

    @Test
    void pagar_sinIniciarSesion_devuelve401YNoPaga() throws Exception {
        mockMvc.perform(post(RUTA).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(CUERPO))
                .andExpect(status().isUnauthorized());
        verify(servicioPago, never()).pagar(anyString(), anyBoolean(), any());
    }
}