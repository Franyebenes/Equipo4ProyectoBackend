package com.esibuy.esibuy_backend.controlador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.esibuy.esibuy_backend.configuracion.ConfiguracionAplicacion;
import com.esibuy.esibuy_backend.configuracion.ConfiguracionLogin;
import com.esibuy.esibuy_backend.configuracion.FiltroCorrelacionId;
import com.esibuy.esibuy_backend.modelo.Rol;
import com.esibuy.esibuy_backend.seguridad.ConfiguracionSeguridad;
import com.esibuy.esibuy_backend.seguridad.EstablecedorSesion;
import com.esibuy.esibuy_backend.servicio.ResultadoAutenticacion;
import com.jayway.jsonpath.JsonPath;

/**
 * Consulta de la sesión actual (GET /api/auth/me) y cierre de sesión (POST /api/auth/logout), con la seguridad real.
 * No hay servicios que simular: las sesiones se crean con el EstablecedorSesion real, igual que hace el login.
 *
 * Decisiones que fijan estas pruebas:
 *  - /me devuelve 200 con {id, email, nombre, rol} si hay una sesión autenticada y 401 en cualquier otro caso
 *    (sin sesión, o con una sesión anónima como la que solo guarda el token CSRF). Es lo que el frontend usa para
 *    recuperar al usuario tras recargar la página.
 *  - /logout exige token CSRF (403 sin él), invalida la sesión y responde 204. Sin sesión también responde 204
 *    (es idempotente: cerrar una sesión que ya no existe no es un error).
 */
@WebMvcTest(ControladorSesion.class)
@Import({ConfiguracionSeguridad.class, ConfiguracionAplicacion.class, ConfiguracionLogin.class})
class ControladorSesionTest {

    private static final String RUTA_ME = "/api/auth/me";
    private static final String RUTA_LOGOUT = "/api/auth/logout";
    private static final ResultadoAutenticacion USUARIO = new ResultadoAutenticacion(
            "665f1c2e9b1e8a3d4c5b6a79", "ana.garcia@ejemplo.es", "Ana", Rol.CLIENTE);

    @Autowired
    private WebApplicationContext contexto;
    @Autowired
    private EstablecedorSesion establecedorSesion;

    private MockMvc mockMvc;

    @BeforeEach
    void prepararMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(contexto)
                .addFilters(new FiltroCorrelacionId())
                .apply(springSecurity())
                .build();
    }

    /** Una sesión tal y como la deja un login correcto. */
    private MockHttpSession sesionIniciadaPor(ResultadoAutenticacion usuario) {
        MockHttpServletRequest peticion = new MockHttpServletRequest();
        establecedorSesion.establecer(peticion, usuario);
        return (MockHttpSession) peticion.getSession(false);
    }

    // ------------------------------------------------------------------ /me

    @Test
    void me_sesionAutenticada_devuelve200ConLosDatosPublicosDelUsuario() throws Exception {
        // Given
        MockHttpSession sesion = sesionIniciadaPor(USUARIO);

        // When
        MvcResult resultado = mockMvc.perform(get(RUTA_ME).session(sesion))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(USUARIO.id()))
                .andExpect(jsonPath("$.email").value(USUARIO.email()))
                .andExpect(jsonPath("$.nombre").value(USUARIO.nombre()))
                .andExpect(jsonPath("$.rol").value("CLIENTE"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
                .andReturn();

        // Then: exactamente esos campos, sin identificador de sesión ni secretos
        Map<String, Object> campos = JsonPath.read(resultado.getResponse().getContentAsString(StandardCharsets.UTF_8), "$");
        assertThat(campos.keySet()).containsExactlyInAnyOrder("id", "email", "nombre", "rol");
    }

    @Test
    void me_sinSesion_devuelve401SinWwwAuthenticate() throws Exception {
        // When / Then
        mockMvc.perform(get(RUTA_ME))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE));
    }

    @Test
    void me_sesionAnonimaSinUsuario_devuelve401() throws Exception {
        // Given: una sesión que solo guarda el token CSRF, sin contexto de seguridad
        MockHttpSession sesionAnonima = new MockHttpSession();
        sesionAnonima.setAttribute("token-csrf", "valor");

        // When / Then
        mockMvc.perform(get(RUTA_ME).session(sesionAnonima)).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ /logout

    @Test
    void logout_sesionAutenticadaConTokenCsrf_invalidaLaSesionYDevuelve204() throws Exception {
        // Given
        MockHttpSession sesion = sesionIniciadaPor(USUARIO);

        // When
        MvcResult resultado = mockMvc.perform(post(RUTA_LOGOUT).session(sesion).with(csrf()))
                .andExpect(status().isNoContent())
                .andReturn();

        // Then: la sesión queda invalidada y la respuesta no tiene cuerpo
        assertThat(sesion.isInvalid()).isTrue();
        assertThat(resultado.getResponse().getContentAsString()).isEmpty();
    }

    @Test
    void logout_sinTokenCsrf_devuelve403YNoCierraLaSesion() throws Exception {
        // Given
        MockHttpSession sesion = sesionIniciadaPor(USUARIO);

        // When / Then: un sitio ajeno no puede cerrar la sesión del usuario
        mockMvc.perform(post(RUTA_LOGOUT).session(sesion)).andExpect(status().isForbidden());
        assertThat(sesion.isInvalid()).isFalse();
    }

    @Test
    void logout_sinSesionConTokenCsrf_devuelve204() throws Exception {
        // When / Then: es idempotente
        mockMvc.perform(post(RUTA_LOGOUT).with(csrf())).andExpect(status().isNoContent());
    }
}
