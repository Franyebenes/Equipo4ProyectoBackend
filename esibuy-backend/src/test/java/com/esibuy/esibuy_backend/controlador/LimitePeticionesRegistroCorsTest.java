package com.esibuy.esibuy_backend.controlador;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * El 429 del limite de peticiones debe llevar las cabeceras CORS: si no las lleva, el navegador bloquea la
 * respuesta y el frontend no ve un 429 sino un error de red, sin poder leer Retry-After.
 *
 * <p>Usa la aplicacion completa con sus filtros reales y en su orden real: el fallo estaba en el orden del
 * filtro respecto al de CORS de Spring Security, algo que no se aprecia si se montan los filtros a mano
 * (como hace {@link LimitePeticionesRegistroTest}). No necesita MongoDB: con un cuerpo incompleto el servicio
 * rechaza el registro antes de consultar la base de datos.
 */
@SpringBootTest(properties = {
        "esibuy.seguridad.pepper=pepper-de-test",
        "esibuy.limite-registro.max-peticiones=1",
        "esibuy.pwned-passwords.habilitado=false"})
@AutoConfigureMockMvc
class LimitePeticionesRegistroCorsTest {

    private static final String RUTA_REGISTRO = "/api/auth/registro";
    private static final String ORIGEN_FRONTEND = "http://localhost:43127";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void registrar_superadoElLimite_el429LlevaCabecerasCorsYRetryAfterLegibles() throws Exception {
        // Given: la primera peticion agota el limite de 1 (responde 400 por datos incompletos)
        mockMvc.perform(post(RUTA_REGISTRO)
                        .header("Origin", ORIGEN_FRONTEND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tipoCuenta\":\"CLIENTE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Access-Control-Allow-Origin", ORIGEN_FRONTEND));

        // When / Then: la segunda se rechaza por el limite, y el navegador debe poder leerla
        mockMvc.perform(post(RUTA_REGISTRO)
                        .header("Origin", ORIGEN_FRONTEND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tipoCuenta\":\"CLIENTE\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Access-Control-Allow-Origin", ORIGEN_FRONTEND))
                .andExpect(header().string("Access-Control-Expose-Headers", containsString("Retry-After")))
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().exists("X-Correlation-Id"));
    }
}
