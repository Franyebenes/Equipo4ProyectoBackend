package com.esibuy.esibuy_backend.controlador;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
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

import com.esibuy.esibuy_backend.dto.ProductoAltaDTO;
import com.esibuy.esibuy_backend.dto.ProductoCatalogoDTO;
import com.esibuy.esibuy_backend.excepcion.ProductoInvalidoException;
import com.esibuy.esibuy_backend.seguridad.ConfiguracionSeguridad;
import com.esibuy.esibuy_backend.servicio.ServicioProducto;

/**
 * HU-15.1 Endpoint POST /api/vendedor/productos: solo vendedores, con token CSRF y siempre con el vendedor de la
 * sesion; mensajes de error que recibe el frontend y limite de tamano propio del alta (lleva una imagen).
 */
@WebMvcTest(ControladorProducto.class)
@Import(ConfiguracionSeguridad.class)
class ControladorProductoAltaTest {

    // El principal de la sesion es el id del usuario (EstablecedorSesion)
    private static final String ID_VENDEDOR = "64b7f0c2a1b2c3d4e5f60001";
    private static final String ID_OTRO_VENDEDOR = "64b7f0c2a1b2c3d4e5f60002";
    private static final String ID_PRODUCTO = "64b7f0c2a1b2c3d4e5f60101";
    private static final String ID_HOGAR = "64b7f0c2a1b2c3d4e5f60202";
    private static final String RUTA = "/api/vendedor/productos";

    private static final ProductoAltaDTO ALTA_VALIDA =
            new ProductoAltaDTO("Lampara", "Lampara de sobremesa.", 80.0, List.of(ID_HOGAR), 4, null);
    private static final ProductoCatalogoDTO LAMPARA = new ProductoCatalogoDTO(ID_PRODUCTO, "Lampara",
            "Lampara de sobremesa.", null, List.of("Hogar"), 80.0, null, null, 4, true);

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private ServicioProducto servicioProducto;

    private static String cuerpo(String imagen) {
        String valorImagen = imagen == null ? "null" : "\"" + imagen + "\"";
        return "{\"nombre\":\"Lampara\",\"descripcion\":\"Lampara de sobremesa.\",\"precio\":80.0,"
                + "\"idCategorias\":[\"" + ID_HOGAR + "\"],\"stock\":4,\"imagen\":" + valorImagen + "}";
    }

    @Test
    @WithMockUser(username = ID_VENDEDOR, roles = "VENDEDOR")
    void crear_vendedor_devuelve201ConElProductoYUsaElVendedorDeLaSesion() throws Exception {
        // Given
        when(servicioProducto.crearProducto(ID_VENDEDOR, ALTA_VALIDA)).thenReturn(LAMPARA);

        // When / Then
        mockMvc.perform(post(RUTA).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(cuerpo(null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(ID_PRODUCTO))
                .andExpect(jsonPath("$.nombre").value("Lampara"))
                .andExpect(jsonPath("$.categorias[0]").value("Hogar"))
                .andExpect(jsonPath("$.precio").value(80.0))
                .andExpect(jsonPath("$.stock").value(4))
                .andExpect(jsonPath("$.visible").value(true));
        verify(servicioProducto).crearProducto(ID_VENDEDOR, ALTA_VALIDA);
    }

    @Test
    @WithMockUser(username = ID_VENDEDOR, roles = "VENDEDOR")
    void crear_datosInvalidos_devuelve400ConElMensajeDelServicio() throws Exception {
        // Given
        when(servicioProducto.crearProducto(anyString(), any()))
                .thenThrow(new ProductoInvalidoException("Rellena todos los campos obligatorios"));

        // When / Then
        mockMvc.perform(post(RUTA).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(cuerpo(null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value("Rellena todos los campos obligatorios"));
    }

    @Test
    @WithMockUser(username = ID_VENDEDOR, roles = "VENDEDOR")
    void crear_conIdVendedorEnElCuerpo_devuelve400YNoCrea() throws Exception {
        // Given: intenta crear el producto a nombre de otro vendedor (el DTO no tiene ese campo)
        String cuerpo = cuerpo(null).replace("{", "{\"idVendedor\":\"" + ID_OTRO_VENDEDOR + "\",");

        // When / Then
        mockMvc.perform(post(RUTA).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value(ManejadorExcepciones.MENSAJE_PETICION_INVALIDA));
        verify(servicioProducto, never()).crearProducto(any(), any());
    }

    @Test
    @WithMockUser(username = ID_VENDEDOR, roles = "VENDEDOR")
    void crear_conImagenDeMasDe16KB_seAceptaPorqueElAltaTieneSuPropioLimite() throws Exception {
        // Given: el resto de peticiones se limita a 16 KB, pero el alta lleva una imagen en base64
        String imagen = "data:image/jpeg;base64," + "A".repeat(50_000);
        when(servicioProducto.crearProducto(eq(ID_VENDEDOR), any())).thenReturn(LAMPARA);

        // When / Then
        mockMvc.perform(post(RUTA).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(cuerpo(imagen)))
                .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(username = ID_VENDEDOR, roles = "VENDEDOR")
    void crear_cuerpoDeMasDe2MB_devuelve413YNoCrea() throws Exception {
        // Given: una "imagen" que supera el limite del alta
        String imagen = "data:image/jpeg;base64," + "A".repeat(LimiteTamanoCuerpoPeticion.TAMANO_MAXIMO_ALTA_PRODUCTO_BYTES);

        // When / Then
        mockMvc.perform(post(RUTA).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(cuerpo(imagen)))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.mensaje").value(ManejadorExcepciones.MENSAJE_CUERPO_DEMASIADO_GRANDE));
        verify(servicioProducto, never()).crearProducto(any(), any());
    }

    @Test
    @WithMockUser(username = ID_VENDEDOR, roles = "VENDEDOR")
    void crear_sinTokenCsrf_devuelve403YNoCrea() throws Exception {
        // When / Then
        mockMvc.perform(post(RUTA).contentType(MediaType.APPLICATION_JSON).content(cuerpo(null)))
                .andExpect(status().isForbidden());
        verify(servicioProducto, never()).crearProducto(any(), any());
    }

    @Test
    @WithMockUser(roles = "CLIENTE")
    void crear_cliente_devuelve403YNoCrea() throws Exception {
        // When / Then
        mockMvc.perform(post(RUTA).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(cuerpo(null)))
                .andExpect(status().isForbidden());
        verify(servicioProducto, never()).crearProducto(any(), any());
    }

    @Test
    void crear_sinIniciarSesion_devuelve401YNoCrea() throws Exception {
        // Given: visitante anonimo

        // When / Then
        mockMvc.perform(post(RUTA).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(cuerpo(null)))
                .andExpect(status().isUnauthorized());
        verify(servicioProducto, never()).crearProducto(any(), any());
    }
}
