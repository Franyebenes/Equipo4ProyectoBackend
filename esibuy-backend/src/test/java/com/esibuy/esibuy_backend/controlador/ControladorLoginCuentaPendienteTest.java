package com.esibuy.esibuy_backend.controlador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.esibuy.esibuy_backend.configuracion.ConfiguracionAplicacion;
import com.esibuy.esibuy_backend.configuracion.ConfiguracionLogin;
import com.esibuy.esibuy_backend.configuracion.FiltroCorrelacionId;
import com.esibuy.esibuy_backend.excepcion.CuentaPendienteActivacionException;
import com.esibuy.esibuy_backend.seguridad.ConfiguracionSeguridad;
import com.esibuy.esibuy_backend.servicio.ServicioAutenticacion;

/**
 * Respuesta del login cuando la cuenta está pendiente de activación: 403 con un código estable que el frontend
 * distingue de un fallo de CSRF (que también es 403 pero sin cuerpo). No crea sesión ni repite el correo.
 *
 * Clase añadida porque ControladorLoginTest pertenece a una fase cerrada.
 */
@WebMvcTest(ControladorLogin.class)
@Import({ConfiguracionSeguridad.class, ConfiguracionAplicacion.class, ConfiguracionLogin.class})
class ControladorLoginCuentaPendienteTest {

    private static final String EMAIL = "ana.garcia@ejemplo.es";
    private static final String CONTRASENA = "Tren-Azul-Lluvia-77";

    @Autowired
    private WebApplicationContext contexto;
    @MockitoBean
    private ServicioAutenticacion servicioAutenticacion;

    private MockMvc mockMvc;

    @BeforeEach
    void prepararMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(contexto)
                .addFilters(new FiltroCorrelacionId())
                .apply(springSecurity())
                .build();
    }

    @Test
    void login_cuentaPendienteDeActivacion_devuelve403ConCodigoYSinSesion() throws Exception {
        // Given
        when(servicioAutenticacion.autenticar(any(), any())).thenThrow(new CuentaPendienteActivacionException());

        // When
        MvcResult resultado = mockMvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + EMAIL + "\",\"contrasena\":\"" + CONTRASENA + "\"}"))
                // Then
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.mensaje").value("Cuenta pendiente de activacion"))
                .andExpect(jsonPath("$.codigo").value("CUENTA_PENDIENTE_DE_ACTIVACION"))
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
                .andReturn();

        assertThat(resultado.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .doesNotContain(EMAIL, CONTRASENA);
        assertThat(resultado.getRequest().getSession(false)).isNull();
    }
}
