package com.esibuy.esibuy_backend.controlador;

import com.esibuy.esibuy_backend.dto.CategoriaDTO;
import com.esibuy.esibuy_backend.seguridad.ConfiguracionSeguridad;
import com.esibuy.esibuy_backend.servicio.CatalogoAvatares;
import com.esibuy.esibuy_backend.servicio.ServicioCategoria;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Endpoints publicos que necesita el formulario de registro: catalogo de avatares y categorias
 * (estas ultimas para el alta de vendedor). Deben ser de solo lectura y accesibles sin autenticar.
 */
@WebMvcTest(ControladorFormularioRegistro.class)
@Import(ConfiguracionSeguridad.class)
class ControladorFormularioRegistroTest {

    private static final String RUTA_AVATARES = "/api/registro/avatares";
    private static final String RUTA_CATEGORIAS = "/api/registro/categorias";

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private CatalogoAvatares catalogoAvatares;
    @MockitoBean
    private ServicioCategoria servicioCategoria;

    @BeforeEach
    void prepararCatalogos() {
        when(catalogoAvatares.listarAvatares()).thenReturn(List.of("avatar-01", "avatar-02"));
        when(servicioCategoria.listarCategorias()).thenReturn(List.of(
                new CategoriaDTO("64b7f0c2a1b2c3d4e5f60718", "Electronica"),
                new CategoriaDTO("64b7f0c2a1b2c3d4e5f60719", "Hogar")));
    }

    @Test
    void listarAvatares_visitanteSinAutenticar_devuelve200ConElCatalogo() throws Exception { // CP-SEG-08
        // Given: visitante anonimo

        // When / Then
        mockMvc.perform(get(RUTA_AVATARES))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0]").value("avatar-01"));
    }

    @Test
    void listarCategorias_visitanteSinAutenticar_devuelve200ConIdYNombre() throws Exception { // CP-SEG-08
        // Given: visitante anonimo

        // When / Then
        mockMvc.perform(get(RUTA_CATEGORIAS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value("64b7f0c2a1b2c3d4e5f60718"))
                .andExpect(jsonPath("$[0].nombre").value("Electronica"));
    }

    @Test
    void endpointsAuxiliares_metodosQueModifican_nuncaRespondenConExito() throws Exception { // CP-SEG-08
        // Given: un visitante intenta modificar los catalogos

        // When
        List<MvcResult> resultados = List.of(
                mockMvc.perform(post(RUTA_AVATARES).content("{}").contentType("application/json")).andReturn(),
                mockMvc.perform(put(RUTA_AVATARES).content("{}").contentType("application/json")).andReturn(),
                mockMvc.perform(delete(RUTA_AVATARES)).andReturn(),
                mockMvc.perform(post(RUTA_CATEGORIAS).content("{}").contentType("application/json")).andReturn(),
                mockMvc.perform(put(RUTA_CATEGORIAS).content("{}").contentType("application/json")).andReturn(),
                mockMvc.perform(delete(RUTA_CATEGORIAS)).andReturn());

        // Then: solo lectura, cualquier metodo que modifique acaba en error de cliente
        assertThat(resultados).allSatisfy(resultado ->
                assertThat(resultado.getResponse().getStatus()).isBetween(400, 499));
    }
}