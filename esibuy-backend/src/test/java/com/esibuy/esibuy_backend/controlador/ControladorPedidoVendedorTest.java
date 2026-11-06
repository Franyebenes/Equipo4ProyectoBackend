package com.esibuy.esibuy_backend.controlador;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import com.esibuy.esibuy_backend.dto.LineaPedidoRecibidoDTO;
import com.esibuy.esibuy_backend.dto.PedidoRecibidoDTO;
import com.esibuy.esibuy_backend.dto.PedidoRecibidoDetalleDTO;
import com.esibuy.esibuy_backend.excepcion.EstadoPedidoNoPermitidoException;
import com.esibuy.esibuy_backend.excepcion.PedidoInvalidoException;
import com.esibuy.esibuy_backend.excepcion.PedidoNoEncontradoException;
import com.esibuy.esibuy_backend.modelo.EstadoPedido;
import com.esibuy.esibuy_backend.seguridad.ConfiguracionSeguridad;
import com.esibuy.esibuy_backend.servicio.ServicioPedidoVendedor;

/**
 * Ver pedidos recibidos y Procesar estado del pedido. Endpoints de /api/vendedor/pedidos: solo el rol VENDEDOR,
 * siempre con el vendedor de la sesion, y las respuestas y mensajes que recibe el frontend.
 */
@WebMvcTest(ControladorPedidoVendedor.class)
@Import(ConfiguracionSeguridad.class)
class ControladorPedidoVendedorTest {

    private static final String ID_VENDEDOR = "64c000000000000000000001";
    private static final String ID_PEDIDO = "64c000000000000000000101";
    private static final String ID_PRODUCTO = "64c000000000000000000021";
    private static final String RUTA = "/api/vendedor/pedidos";
    private static final String RUTA_PEDIDO = RUTA + "/" + ID_PEDIDO;
    private static final String RUTA_ESTADO = RUTA_PEDIDO + "/estado";
    private static final Instant FECHA = Instant.parse("2026-11-02T09:30:00Z");
    private static final PedidoRecibidoDTO PEDIDO = new PedidoRecibidoDTO(ID_PEDIDO, FECHA, "Ana López", 36.0,
            EstadoPedido.PAGADO, 2);
    private static final PedidoRecibidoDetalleDTO DETALLE = detalle(EstadoPedido.PAGADO, FECHA,
            List.of(EstadoPedido.EN_PREPARACION, EstadoPedido.ENVIADO, EstadoPedido.EN_REPARTO,
                    EstadoPedido.ENTREGADO));

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private ServicioPedidoVendedor servicioPedidoVendedor;

    // ---------- GET: listado ----------

    @Test
    @WithMockUser(username = ID_VENDEDOR, roles = "VENDEDOR")
    void listar_vendedor_devuelve200ConSusPedidosYUsaElVendedorDeLaSesion() throws Exception {
        when(servicioPedidoVendedor.listar(ID_VENDEDOR)).thenReturn(List.of(PEDIDO));

        mockMvc.perform(get(RUTA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(ID_PEDIDO))
                .andExpect(jsonPath("$[0].fecha").exists())
                .andExpect(jsonPath("$[0].cliente").value("Ana López"))
                .andExpect(jsonPath("$[0].importe").value(36.0))
                .andExpect(jsonPath("$[0].estado").value("PAGADO"))
                .andExpect(jsonPath("$[0].unidades").value(2));
        verify(servicioPedidoVendedor).listar(ID_VENDEDOR);
    }

    @Test
    @WithMockUser(username = ID_VENDEDOR, roles = "VENDEDOR")
    void listar_sinPedidos_devuelve200ConListaVacia() throws Exception {
        when(servicioPedidoVendedor.listar(ID_VENDEDOR)).thenReturn(List.of());

        mockMvc.perform(get(RUTA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @WithMockUser(roles = "CLIENTE")
    void listar_cliente_devuelve403YNoConsultaNada() throws Exception {
        mockMvc.perform(get(RUTA)).andExpect(status().isForbidden());
        verify(servicioPedidoVendedor, never()).listar(anyString());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void listar_administrador_devuelve403() throws Exception {
        mockMvc.perform(get(RUTA)).andExpect(status().isForbidden());
        verify(servicioPedidoVendedor, never()).listar(anyString());
    }

    @Test
    void listar_sinIniciarSesion_devuelve401() throws Exception {
        mockMvc.perform(get(RUTA)).andExpect(status().isUnauthorized());
        verify(servicioPedidoVendedor, never()).listar(anyString());
    }

    // ---------- GET /{id}: detalle ----------

    @Test
    @WithMockUser(username = ID_VENDEDOR, roles = "VENDEDOR")
    void detalle_vendedor_devuelve200ConDireccionYLineas() throws Exception {
        when(servicioPedidoVendedor.detalle(ID_VENDEDOR, ID_PEDIDO)).thenReturn(DETALLE);

        mockMvc.perform(get(RUTA_PEDIDO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ID_PEDIDO))
                .andExpect(jsonPath("$.cliente").value("Ana López"))
                .andExpect(jsonPath("$.direccionEnvio.ciudad").value("Ciudad Real"))
                .andExpect(jsonPath("$.lineas[0].nombre").value("Teclado"))
                .andExpect(jsonPath("$.lineas[0].subtotal").value(36.0))
                .andExpect(jsonPath("$.fechaActualizacion").exists())
                .andExpect(jsonPath("$.estadosSiguientes[0]").value("EN_PREPARACION"));
    }

    @Test
    @WithMockUser(username = ID_VENDEDOR, roles = "VENDEDOR")
    void detalle_pedidoAjenoOInexistente_devuelve404ConMensaje() throws Exception {
        when(servicioPedidoVendedor.detalle(ID_VENDEDOR, ID_PEDIDO)).thenThrow(new PedidoNoEncontradoException());

        mockMvc.perform(get(RUTA_PEDIDO))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.mensaje").value("El pedido no existe"));
    }

    @Test
    @WithMockUser(roles = "CLIENTE")
    void detalle_cliente_devuelve403YNoConsultaNada() throws Exception {
        mockMvc.perform(get(RUTA_PEDIDO)).andExpect(status().isForbidden());
        verify(servicioPedidoVendedor, never()).detalle(anyString(), anyString());
    }

    // ---------- PATCH /{id}/estado: cambiar estado ----------

    @Test
    @WithMockUser(username = ID_VENDEDOR, roles = "VENDEDOR")
    void cambiarEstado_vendedor_devuelve200ConElPedidoActualizadoYUsaElVendedorDeLaSesion() throws Exception {
        when(servicioPedidoVendedor.cambiarEstado(ID_VENDEDOR, ID_PEDIDO, EstadoPedido.ENVIADO))
                .thenReturn(detalle(EstadoPedido.ENVIADO, Instant.parse("2026-11-03T12:00:00Z"),
                        List.of(EstadoPedido.EN_REPARTO, EstadoPedido.ENTREGADO)));

        mockMvc.perform(patch(RUTA_ESTADO).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estado\":\"ENVIADO\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("ENVIADO"))
                .andExpect(jsonPath("$.fechaActualizacion").exists())
                .andExpect(jsonPath("$.estadosSiguientes[0]").value("EN_REPARTO"));
        verify(servicioPedidoVendedor).cambiarEstado(ID_VENDEDOR, ID_PEDIDO, EstadoPedido.ENVIADO);
    }

    @Test
    @WithMockUser(username = ID_VENDEDOR, roles = "VENDEDOR")
    void cambiarEstado_noPermitido_devuelve409ConElMensaje() throws Exception {
        when(servicioPedidoVendedor.cambiarEstado(ID_VENDEDOR, ID_PEDIDO, EstadoPedido.PAGADO))
                .thenThrow(new EstadoPedidoNoPermitidoException("El envío solo puede avanzar"));

        mockMvc.perform(patch(RUTA_ESTADO).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estado\":\"PAGADO\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensaje").value("El envío solo puede avanzar"));
    }

    @Test
    @WithMockUser(username = ID_VENDEDOR, roles = "VENDEDOR")
    void cambiarEstado_pedidoAjeno_devuelve404() throws Exception {
        when(servicioPedidoVendedor.cambiarEstado(ID_VENDEDOR, ID_PEDIDO, EstadoPedido.ENVIADO))
                .thenThrow(new PedidoNoEncontradoException());

        mockMvc.perform(patch(RUTA_ESTADO).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estado\":\"ENVIADO\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = ID_VENDEDOR, roles = "VENDEDOR")
    void cambiarEstado_sinEstado_devuelve400ConElMensaje() throws Exception {
        when(servicioPedidoVendedor.cambiarEstado(ID_VENDEDOR, ID_PEDIDO, null))
                .thenThrow(new PedidoInvalidoException("Indica el nuevo estado del pedido"));

        mockMvc.perform(patch(RUTA_ESTADO).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value("Indica el nuevo estado del pedido"));
    }

    @Test
    @WithMockUser(username = ID_VENDEDOR, roles = "VENDEDOR")
    void cambiarEstado_estadoQueNoExiste_devuelve400SinLlegarAlServicio() throws Exception {
        mockMvc.perform(patch(RUTA_ESTADO).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estado\":\"VOLANDO\"}"))
                .andExpect(status().isBadRequest());
        verify(servicioPedidoVendedor, never()).cambiarEstado(anyString(), anyString(), any());
    }

    @Test
    @WithMockUser(username = ID_VENDEDOR, roles = "VENDEDOR")
    void cambiarEstado_sinTokenCsrf_devuelve403() throws Exception {
        mockMvc.perform(patch(RUTA_ESTADO)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estado\":\"ENVIADO\"}"))
                .andExpect(status().isForbidden());
        verify(servicioPedidoVendedor, never()).cambiarEstado(anyString(), anyString(), any());
    }

    @Test
    @WithMockUser(roles = "CLIENTE")
    void cambiarEstado_cliente_devuelve403() throws Exception {
        mockMvc.perform(patch(RUTA_ESTADO).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estado\":\"ENVIADO\"}"))
                .andExpect(status().isForbidden());
        verify(servicioPedidoVendedor, never()).cambiarEstado(anyString(), anyString(), any());
    }

    private static PedidoRecibidoDetalleDTO detalle(EstadoPedido estado, Instant actualizado,
                                                    List<EstadoPedido> siguientes) {
        return new PedidoRecibidoDetalleDTO(ID_PEDIDO, FECHA, "Ana López", 36.0, estado, 2, actualizado, siguientes,
                new DireccionEnvioDTO("Calle Mayor 1", "Ciudad Real", "13001"),
                List.of(new LineaPedidoRecibidoDTO(ID_PRODUCTO, "Teclado", 2, 18.0, 36.0)));
    }
}
