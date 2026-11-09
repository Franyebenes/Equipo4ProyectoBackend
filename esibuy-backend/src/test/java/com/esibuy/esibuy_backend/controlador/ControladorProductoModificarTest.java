package com.esibuy.esibuy_backend.controlador;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.esibuy.esibuy_backend.dto.ProductoAltaDTO;
import com.esibuy.esibuy_backend.dto.ProductoCatalogoDTO;
import com.esibuy.esibuy_backend.excepcion.ProductoInvalidoException;
import com.esibuy.esibuy_backend.excepcion.ProductoNoEncontradoException;
import com.esibuy.esibuy_backend.excepcion.StockModificadoException;
import com.esibuy.esibuy_backend.seguridad.ConfiguracionSeguridad;
import com.esibuy.esibuy_backend.servicio.ServicioProducto;

/**
 * HU Modificar producto. Endpoint PUT /api/vendedor/productos/{id}: solo vendedores, con token CSRF y siempre sobre
 * el catalogo del vendedor de la sesion; respuestas y mensajes que recibe el frontend y limite de tamano para la
 * imagen (el mismo que el alta, porque recibe los mismos datos).
 */
@WebMvcTest(ControladorProducto.class)
@Import(ConfiguracionSeguridad.class)
class ControladorProductoModificarTest {

    // El principal de la sesion es el id del usuario (EstablecedorSesion)
    private static final String ID_VENDEDOR = "64b7f0c2a1b2c3d4e5f60001";
    private static final String ID_PRODUCTO = "64b7f0c2a1b2c3d4e5f60101";
    private static final String ID_HOGAR = "64b7f0c2a1b2c3d4e5f60202";
    private static final String RUTA = "/api/vendedor/productos/" + ID_PRODUCTO;

    private static final ProductoAltaDTO CAMBIOS = new ProductoAltaDTO("Lampara LED", "Lampara de sobremesa.", 95.5,
            List.of(ID_HOGAR), 12, null, false, 20.0, null, null);
    private static final ProductoCatalogoDTO LAMPARA_LED = new ProductoCatalogoDTO(ID_PRODUCTO, "Lampara LED",
            "Lampara de sobremesa.", null, List.of("Hogar"), 95.5, 20.0, null, 12, false, false);

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private ServicioProducto servicioProducto;

    private static String cuerpo(String imagen) {
        String valorImagen = imagen == null ? "null" : "\"" + imagen + "\"";
        return "{\"nombre\":\"Lampara LED\",\"descripcion\":\"Lampara de sobremesa.\",\"precio\":95.5,"
                + "\"descuento\":20.0,\"idCategorias\":[\"" + ID_HOGAR + "\"],\"stock\":12,\"activo\":false,"
                + "\"imagen\":" + valorImagen + "}";
    }

    @Test
    @WithMockUser(username = ID_VENDEDOR, roles = "VENDEDOR")
    void modificar_vendedor_devuelve200ConElProductoActualizadoYUsaElVendedorDeLaSesion() throws Exception {
        // Given
        when(servicioProducto.modificarProducto(ID_VENDEDOR, ID_PRODUCTO, CAMBIOS, null)).thenReturn(LAMPARA_LED);

        // When / Then
        mockMvc.perform(put(RUTA).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(cuerpo(null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ID_PRODUCTO))
                .andExpect(jsonPath("$.nombre").value("Lampara LED"))
                .andExpect(jsonPath("$.categorias[0]").value("Hogar"))
                .andExpect(jsonPath("$.precio").value(95.5))
                .andExpect(jsonPath("$.descuento").value(20.0))
                .andExpect(jsonPath("$.stock").value(12))
                .andExpect(jsonPath("$.visible").value(false));
        verify(servicioProducto).modificarProducto(ID_VENDEDOR, ID_PRODUCTO, CAMBIOS, null);
    }

    @Test
    @WithMockUser(username = ID_VENDEDOR, roles = "VENDEDOR")
    void modificar_conStockAnterior_pasaAlServicioLaCantidadQueVioElVendedor() throws Exception {
        // Given
        when(servicioProducto.modificarProducto(ID_VENDEDOR, ID_PRODUCTO, CAMBIOS, 4)).thenReturn(LAMPARA_LED);

        // When / Then
        mockMvc.perform(put(RUTA).param("stockAnterior", "4").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(cuerpo(null)))
                .andExpect(status().isOk());
        verify(servicioProducto).modificarProducto(ID_VENDEDOR, ID_PRODUCTO, CAMBIOS, 4);
    }

    @Test
    @WithMockUser(username = ID_VENDEDOR, roles = "VENDEDOR")
    void modificar_stockCambiadoMientrasLoEditaba_devuelve409ConLaCantidadActual() throws Exception {
        // Given
        when(servicioProducto.modificarProducto(ID_VENDEDOR, ID_PRODUCTO, CAMBIOS, 4))
                .thenThrow(new StockModificadoException(3));

        // When / Then
        mockMvc.perform(put(RUTA).param("stockAnterior", "4").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(cuerpo(null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.stockActual").value(3));
    }

    @Test
    @WithMockUser(username = ID_VENDEDOR, roles = "VENDEDOR")
    void modificar_datosInvalidos_devuelve400ConElMensajeDelServicio() throws Exception {
        // Given
        when(servicioProducto.modificarProducto(anyString(), anyString(), any(), any()))
                .thenThrow(new ProductoInvalidoException("Rellena todos los campos obligatorios"));

        // When / Then
        mockMvc.perform(put(RUTA).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(cuerpo(null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value("Rellena todos los campos obligatorios"));
    }

    @Test
    @WithMockUser(username = ID_VENDEDOR, roles = "VENDEDOR")
    void modificar_productoAjenoOInexistente_devuelve404ConMensaje() throws Exception {
        // Given
        when(servicioProducto.modificarProducto(ID_VENDEDOR, ID_PRODUCTO, CAMBIOS, null))
                .thenThrow(new ProductoNoEncontradoException());

        // When / Then
        mockMvc.perform(put(RUTA).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(cuerpo(null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.mensaje").value("El producto no existe en tu catálogo"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"idVendedor\":\"64b7f0c2a1b2c3d4e5f60002\""})
    @WithMockUser(username = ID_VENDEDOR, roles = "VENDEDOR")
    void modificar_conCamposQueElVendedorNoPuedeCambiar_devuelve400YNoModifica(String campo) throws Exception {
        // Given: intenta pasar el producto a otro vendedor, que solo se toma de la sesion
        String cuerpo = cuerpo(null).replace("{", "{" + campo + ",");

        // When / Then
        mockMvc.perform(put(RUTA).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value(ManejadorExcepciones.MENSAJE_PETICION_INVALIDA));
        verify(servicioProducto, never()).modificarProducto(any(), any(), any(), any());
    }

    @Test
    @WithMockUser(username = ID_VENDEDOR, roles = "VENDEDOR")
    void modificar_precioQueNoEsUnNumero_devuelve400YNoModifica() throws Exception {
        // Given
        String cuerpo = cuerpo(null).replace("95.5", "\"barato\"");

        // When / Then
        mockMvc.perform(put(RUTA).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                .andExpect(status().isBadRequest());
        verify(servicioProducto, never()).modificarProducto(any(), any(), any(), any());
    }

    @Test
    @WithMockUser(username = ID_VENDEDOR, roles = "VENDEDOR")
    void modificar_conImagenDeMasDe16KB_seAceptaPorqueTieneElLimiteDelAlta() throws Exception {
        // Given: el resto de peticiones se limita a 16 KB, pero la modificacion puede llevar la imagen en base64
        String imagen = "data:image/jpeg;base64," + "A".repeat(50_000);
        when(servicioProducto.modificarProducto(eq(ID_VENDEDOR), eq(ID_PRODUCTO), any(), any()))
                .thenReturn(LAMPARA_LED);

        // When / Then
        mockMvc.perform(put(RUTA).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(cuerpo(imagen)))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = ID_VENDEDOR, roles = "VENDEDOR")
    void modificar_cuerpoDeMasDe2MB_devuelve413YNoModifica() throws Exception {
        // Given: una "imagen" que supera el limite
        String imagen = "data:image/jpeg;base64,"
                + "A".repeat(LimiteTamanoCuerpoPeticion.TAMANO_MAXIMO_ALTA_PRODUCTO_BYTES);

        // When / Then
        mockMvc.perform(put(RUTA).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(cuerpo(imagen)))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.mensaje").value(ManejadorExcepciones.MENSAJE_CUERPO_DEMASIADO_GRANDE));
        verify(servicioProducto, never()).modificarProducto(any(), any(), any(), any());
    }

    @Test
    @WithMockUser(username = ID_VENDEDOR, roles = "VENDEDOR")
    void modificar_sinTokenCsrf_devuelve403YNoModifica() throws Exception {
        // When / Then
        mockMvc.perform(put(RUTA).contentType(MediaType.APPLICATION_JSON).content(cuerpo(null)))
                .andExpect(status().isForbidden());
        verify(servicioProducto, never()).modificarProducto(any(), any(), any(), any());
    }

    @Test
    @WithMockUser(roles = "CLIENTE")
    void modificar_cliente_devuelve403YNoModifica() throws Exception {
        // When / Then
        mockMvc.perform(put(RUTA).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(cuerpo(null)))
                .andExpect(status().isForbidden());
        verify(servicioProducto, never()).modificarProducto(any(), any(), any(), any());
    }

    @Test
    void modificar_sinIniciarSesion_devuelve401YNoModifica() throws Exception {
        // When / Then
        mockMvc.perform(put(RUTA).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(cuerpo(null)))
                .andExpect(status().isUnauthorized());
        verify(servicioProducto, never()).modificarProducto(any(), any(), any(), any());
    }
}
