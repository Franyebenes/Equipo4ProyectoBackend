package com.esibuy.esibuy_backend.controlador;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

import com.esibuy.esibuy_backend.dto.CategoriaDTO;
import com.esibuy.esibuy_backend.excepcion.CategoriaDuplicadaException;
import com.esibuy.esibuy_backend.excepcion.CategoriaNoEncontradaException;
import com.esibuy.esibuy_backend.seguridad.ConfiguracionSeguridad;
import com.esibuy.esibuy_backend.servicio.ServicioCategoria;

/**
 * HU-11.3 Endpoints GET y PUT /api/admin/categorias/{id}: respuestas HTTP y mensajes que recibe el frontend.
 */
@WebMvcTest(ControladorCategoria.class)
@Import(ConfiguracionSeguridad.class)
class ControladorCategoriaModificarTest {

    private static final String ID = "64b7f0c2a1b2c3d4e5f60718";
    private static final String RUTA = "/api/admin/categorias/" + ID;
    private static final String CUERPO_VALIDO =
            "{\"nombre\":\"Moda y calzado\",\"descripcion\":\"Ropa, zapatos y complementos.\"}";

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private ServicioCategoria servicioCategoria;

    @Test
    @WithMockUser(roles = "ADMIN")
    void obtener_administrador_devuelve200ConLosDatosActuales() throws Exception {
        // Given
        when(servicioCategoria.obtenerCategoria(ID)).thenReturn(new CategoriaDTO(ID, "Moda", "Ropa y complementos."));

        // When / Then
        mockMvc.perform(get(RUTA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ID))
                .andExpect(jsonPath("$.nombre").value("Moda"))
                .andExpect(jsonPath("$.descripcion").value("Ropa y complementos."));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void obtener_categoriaInexistente_devuelve404ConMensaje() throws Exception {
        // Given
        when(servicioCategoria.obtenerCategoria(ID)).thenThrow(new CategoriaNoEncontradaException());

        // When / Then
        mockMvc.perform(get(RUTA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.mensaje").value("La categoría no existe o ya ha sido eliminada"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void modificar_datosValidos_devuelve200ConLaCategoriaModificada() throws Exception {
        // Given
        CategoriaDTO recibida = new CategoriaDTO(null, "Moda y calzado", "Ropa, zapatos y complementos.");
        when(servicioCategoria.modificarCategoria(ID, recibida))
                .thenReturn(new CategoriaDTO(ID, "Moda y calzado", "Ropa, zapatos y complementos."));

        // When / Then
        mockMvc.perform(put(RUTA).contentType(MediaType.APPLICATION_JSON).content(CUERPO_VALIDO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ID))
                .andExpect(jsonPath("$.nombre").value("Moda y calzado"))
                .andExpect(jsonPath("$.descripcion").value("Ropa, zapatos y complementos."));
        verify(servicioCategoria).modificarCategoria(ID, recibida);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void modificar_nombreVacio_devuelve400YNoLlamaAlServicio() throws Exception {
        // When / Then
        mockMvc.perform(put(RUTA).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"   \",\"descripcion\":\"Ropa y complementos.\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value(ManejadorExcepciones.MENSAJE_PETICION_INVALIDA));
        verify(servicioCategoria, never()).modificarCategoria(anyString(), any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void modificar_descripcionDemasiadoLarga_devuelve400YNoLlamaAlServicio() throws Exception {
        // Given: 201 caracteres, uno mas del maximo
        String cuerpo = "{\"nombre\":\"Moda\",\"descripcion\":\"" + "a".repeat(201) + "\"}";

        // When / Then
        mockMvc.perform(put(RUTA).contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                .andExpect(status().isBadRequest());
        verify(servicioCategoria, never()).modificarCategoria(anyString(), any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void modificar_nombreRepetido_devuelve409ConElMotivo() throws Exception {
        // Given
        when(servicioCategoria.modificarCategoria(anyString(), any()))
                .thenThrow(new CategoriaDuplicadaException("Moda y calzado"));

        // When / Then
        mockMvc.perform(put(RUTA).contentType(MediaType.APPLICATION_JSON).content(CUERPO_VALIDO))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensaje").value("Ya existe una categoría con el nombre «Moda y calzado»"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void modificar_categoriaInexistente_devuelve404ConMensaje() throws Exception {
        // Given
        when(servicioCategoria.modificarCategoria(anyString(), any())).thenThrow(new CategoriaNoEncontradaException());

        // When / Then
        mockMvc.perform(put(RUTA).contentType(MediaType.APPLICATION_JSON).content(CUERPO_VALIDO))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.mensaje").value("La categoría no existe o ya ha sido eliminada"));
    }

    @Test
    void modificar_sinIniciarSesion_devuelve403YNoModifica() throws Exception {
        // Given: visitante anonimo

        // When / Then
        mockMvc.perform(put(RUTA).contentType(MediaType.APPLICATION_JSON).content(CUERPO_VALIDO))
                .andExpect(status().isForbidden());
        verify(servicioCategoria, never()).modificarCategoria(anyString(), any());
    }
}
