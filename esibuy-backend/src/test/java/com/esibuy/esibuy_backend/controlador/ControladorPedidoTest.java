package com.esibuy.esibuy_backend.controlador;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import com.esibuy.esibuy_backend.dto.HistorialPedidosDTO;
import com.esibuy.esibuy_backend.dto.LineaPedidoDTO;
import com.esibuy.esibuy_backend.dto.PedidoDTO;
import com.esibuy.esibuy_backend.excepcion.EstadoPedidoNoPermitidoException;
import com.esibuy.esibuy_backend.excepcion.PedidoInvalidoException;
import com.esibuy.esibuy_backend.excepcion.PedidoNoEncontradoException;
import com.esibuy.esibuy_backend.excepcion.ProductoYaValoradoException;
import com.esibuy.esibuy_backend.excepcion.ValoracionInvalidaException;
import com.esibuy.esibuy_backend.modelo.EstadoPedido;
import com.esibuy.esibuy_backend.seguridad.ConfiguracionSeguridad;
import com.esibuy.esibuy_backend.servicio.ServicioPedido;
import com.esibuy.esibuy_backend.servicio.ServicioValoracion;

/**
 * HU-26 a HU-30. Endpoints de /api/pedidos: solo clientes y premium, siempre con el cliente de la sesion, con token
 * CSRF en los que modifican datos, y las respuestas y mensajes que recibe el frontend.
 */
@WebMvcTest(ControladorPedido.class)
@Import(ConfiguracionSeguridad.class)
class ControladorPedidoTest {

    private static final String ID_CLIENTE = "64b7f0c2a1b2c3d4e5f60001";
    private static final String ID_PEDIDO = "64b7f0c2a1b2c3d4e5f60101";
    private static final String ID_PRODUCTO = "64b7f0c2a1b2c3d4e5f60201";
    private static final String RUTA = "/api/pedidos";
    private static final String RUTA_PEDIDO = RUTA + "/" + ID_PEDIDO;
    private static final String RUTA_VALORACION = RUTA_PEDIDO + "/productos/" + ID_PRODUCTO + "/valoracion";
    private static final String DIRECCION_JSON =
            "{\"direccionEnvio\":{\"calle\":\"Calle Mayor 1\",\"ciudad\":\"Ciudad Real\",\"codigoPostal\":\"13001\"}}";
    private static final DireccionEnvioDTO DIRECCION = new DireccionEnvioDTO("Calle Mayor 1", "Ciudad Real", "13001");
    private static final PedidoDTO PEDIDO = new PedidoDTO(ID_PEDIDO, EstadoPedido.PAGADO,
            Instant.parse("2026-10-24T10:15:30Z"), 36.0, DIRECCION,
            List.of(new LineaPedidoDTO(ID_PRODUCTO, "Teclado", 2, 18.0, 36.0, null)));

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private ServicioPedido servicioPedido;
    @MockitoBean
    private ServicioValoracion servicioValoracion;

    // ---------- HU-26 POST: realizar pedido ----------

    @Test
    @WithMockUser(username = ID_CLIENTE, roles = "CLIENTE")
    void realizar_cliente_devuelve201ConElPedidoYUsaElClienteDeLaSesion() throws Exception {
        when(servicioPedido.realizar(ID_CLIENTE, false, DIRECCION)).thenReturn(PEDIDO);

        mockMvc.perform(post(RUTA).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(DIRECCION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(ID_PEDIDO))
                .andExpect(jsonPath("$.estado").value("PAGADO"))
                .andExpect(jsonPath("$.precioTotal").value(36.0))
                .andExpect(jsonPath("$.lineas[0].nombre").value("Teclado"));
    }

    @Test
    @WithMockUser(username = ID_CLIENTE, roles = "PREMIUM")
    void realizar_premium_indicaAlServicioQueEsPremium() throws Exception {
        when(servicioPedido.realizar(ID_CLIENTE, true, DIRECCION)).thenReturn(PEDIDO);

        mockMvc.perform(post(RUTA).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(DIRECCION_JSON))
                .andExpect(status().isCreated());
        verify(servicioPedido).realizar(ID_CLIENTE, true, DIRECCION);
    }

    @Test
    @WithMockUser(username = ID_CLIENTE, roles = "CLIENTE")
    void realizar_carritoVacio_devuelve400ConMensaje() throws Exception {
        when(servicioPedido.realizar(eq(ID_CLIENTE), anyBoolean(), any()))
                .thenThrow(new PedidoInvalidoException("Tu carrito está vacío"));

        mockMvc.perform(post(RUTA).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(DIRECCION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value("Tu carrito está vacío"));
    }

    @Test
    @WithMockUser(roles = "VENDEDOR")
    void realizar_vendedor_devuelve403YNoCreaNada() throws Exception {
        mockMvc.perform(post(RUTA).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(DIRECCION_JSON))
                .andExpect(status().isForbidden());
        verify(servicioPedido, never()).realizar(anyString(), anyBoolean(), any());
    }

    @Test
    void realizar_sinIniciarSesion_devuelve401() throws Exception {
        mockMvc.perform(post(RUTA).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(DIRECCION_JSON))
                .andExpect(status().isUnauthorized());
        verify(servicioPedido, never()).realizar(anyString(), anyBoolean(), any());
    }

    @Test
    @WithMockUser(username = ID_CLIENTE, roles = "CLIENTE")
    void realizar_sinTokenCsrf_devuelve403() throws Exception {
        mockMvc.perform(post(RUTA).contentType(MediaType.APPLICATION_JSON).content(DIRECCION_JSON))
                .andExpect(status().isForbidden());
        verify(servicioPedido, never()).realizar(anyString(), anyBoolean(), any());
    }

    // ---------- HU-27 GET: historial ----------

    @Test
    @WithMockUser(username = ID_CLIENTE, roles = "CLIENTE")
    void historial_cliente_devuelve200ConLasDosListas() throws Exception {
        when(servicioPedido.historial(ID_CLIENTE)).thenReturn(new HistorialPedidosDTO(List.of(PEDIDO), List.of()));

        mockMvc.perform(get(RUTA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enProceso[0].id").value(ID_PEDIDO))
                .andExpect(jsonPath("$.enProceso[0].lineas[0].valoracion").doesNotExist())
                .andExpect(jsonPath("$.historicos").isEmpty());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void historial_administrador_devuelve403() throws Exception {
        mockMvc.perform(get(RUTA)).andExpect(status().isForbidden());
        verify(servicioPedido, never()).historial(anyString());
    }

    // ---------- HU-28 POST: cancelar ----------

    @Test
    @WithMockUser(username = ID_CLIENTE, roles = "CLIENTE")
    void cancelar_pedidoPagado_devuelve200ConElPedidoCancelado() throws Exception {
        when(servicioPedido.cancelar(ID_CLIENTE, ID_PEDIDO)).thenReturn(new PedidoDTO(ID_PEDIDO,
                EstadoPedido.CANCELADO, PEDIDO.fecha(), 36.0, DIRECCION, PEDIDO.lineas()));

        mockMvc.perform(post(RUTA_PEDIDO + "/cancelar").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("CANCELADO"));
    }

    @Test
    @WithMockUser(username = ID_CLIENTE, roles = "CLIENTE")
    void cancelar_pedidoYaEnviado_devuelve409ConMensaje() throws Exception {
        when(servicioPedido.cancelar(ID_CLIENTE, ID_PEDIDO))
                .thenThrow(new EstadoPedidoNoPermitidoException("Solo se puede cancelar un pedido pagado que aún no se ha enviado"));

        mockMvc.perform(post(RUTA_PEDIDO + "/cancelar").with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensaje").value("Solo se puede cancelar un pedido pagado que aún no se ha enviado"));
    }

    @Test
    @WithMockUser(username = ID_CLIENTE, roles = "CLIENTE")
    void cancelar_pedidoDeOtroCliente_devuelve404() throws Exception {
        when(servicioPedido.cancelar(ID_CLIENTE, ID_PEDIDO)).thenThrow(new PedidoNoEncontradoException());

        mockMvc.perform(post(RUTA_PEDIDO + "/cancelar").with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.mensaje").value("El pedido no existe"));
    }

    // ---------- HU-30 POST: confirmar recepcion y valorar ----------

    @Test
    @WithMockUser(username = ID_CLIENTE, roles = "CLIENTE")
    void confirmarRecepcion_cliente_devuelve200ConElPedidoFinalizado() throws Exception {
        when(servicioPedido.confirmarRecepcion(ID_CLIENTE, ID_PEDIDO)).thenReturn(new PedidoDTO(ID_PEDIDO,
                EstadoPedido.FINALIZADO, PEDIDO.fecha(), 36.0, DIRECCION, PEDIDO.lineas()));

        mockMvc.perform(post(RUTA_PEDIDO + "/recepcion").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("FINALIZADO"));
    }

    @Test
    @WithMockUser(username = ID_CLIENTE, roles = "CLIENTE")
    void valorar_cliente_devuelve204YUsaElClienteDeLaSesion() throws Exception {
        mockMvc.perform(post(RUTA_VALORACION).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"puntos\":4}"))
                .andExpect(status().isNoContent());
        verify(servicioValoracion).valorar(ID_CLIENTE, ID_PEDIDO, ID_PRODUCTO, 4);
    }

    @Test
    @WithMockUser(username = ID_CLIENTE, roles = "CLIENTE")
    void valorar_puntosFueraDeRango_devuelve400ConMensaje() throws Exception {
        doThrow(new ValoracionInvalidaException()).when(servicioValoracion).valorar(ID_CLIENTE, ID_PEDIDO, ID_PRODUCTO, 9);

        mockMvc.perform(post(RUTA_VALORACION).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"puntos\":9}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value("La valoración debe estar entre 1 y 5 estrellas"));
    }

    @Test
    @WithMockUser(username = ID_CLIENTE, roles = "CLIENTE")
    void valorar_productoYaValorado_devuelve409ConMensaje() throws Exception {
        doThrow(new ProductoYaValoradoException()).when(servicioValoracion).valorar(ID_CLIENTE, ID_PEDIDO, ID_PRODUCTO, 4);

        mockMvc.perform(post(RUTA_VALORACION).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"puntos\":4}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensaje").value("Ya has valorado este producto"));
    }

    @Test
    @WithMockUser(username = ID_CLIENTE, roles = "CLIENTE")
    void valorar_sinTokenCsrf_devuelve403() throws Exception {
        mockMvc.perform(post(RUTA_VALORACION).contentType(MediaType.APPLICATION_JSON).content("{\"puntos\":4}"))
                .andExpect(status().isForbidden());
        verify(servicioValoracion, never()).valorar(anyString(), anyString(), anyString(), any());
    }
}
