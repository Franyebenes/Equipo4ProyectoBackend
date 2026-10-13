package com.esibuy.esibuy_backend.controlador;

import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * CORS con credenciales: el frontend y el backend están en orígenes distintos (puertos distintos), y la sesión viaja
 * en una cookie. Para que el navegador envíe y guarde esa cookie en peticiones entre orígenes, el servidor debe
 * responder Access-Control-Allow-Credentials: true con el origen concreto del frontend (nunca con *), y solo para ese
 * origen.
 *
 * <p>Usa la aplicación completa con sus filtros reales, como LimitePeticionesRegistroCorsTest. No necesita MongoDB.
 */
@SpringBootTest(properties = {
        "esibuy.seguridad.pepper=pepper-de-test",
        "esibuy.pwned-passwords.habilitado=false"})
@AutoConfigureMockMvc
class CorsCredencialesTest {

    private static final String ORIGEN_FRONTEND = "http://localhost:43127";
    private static final String ORIGEN_AJENO = "http://sitio-ajeno.example";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void preflight_origenDelFrontend_permiteCredencialesYLasCabecerasDelLogin() throws Exception {
        // When: el navegador pregunta si puede enviar el login con cookies y el token CSRF
        mockMvc.perform(options("/api/auth/login")
                        .header("Origin", ORIGEN_FRONTEND)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type,x-csrf-token"))
                // Then
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ORIGEN_FRONTEND))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
                .andExpect(header().string("Access-Control-Allow-Headers", containsStringIgnoringCase("x-csrf-token")));
    }

    @Test
    void peticionReal_origenDelFrontend_llevaCredencialesYElOrigenConcreto() throws Exception {
        // When: el frontend pide el token CSRF con credenciales
        mockMvc.perform(get("/api/auth/csrf").header("Origin", ORIGEN_FRONTEND))
                // Then: nunca "*", porque con credenciales el navegador la rechazaría
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ORIGEN_FRONTEND))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    void preflight_origenAjeno_seRechazaYNoPermiteCredenciales() throws Exception {
        // When: otro sitio intenta hacer lo mismo
        mockMvc.perform(options("/api/auth/login")
                        .header("Origin", ORIGEN_AJENO)
                        .header("Access-Control-Request-Method", "POST"))
                // Then
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
    }
}
