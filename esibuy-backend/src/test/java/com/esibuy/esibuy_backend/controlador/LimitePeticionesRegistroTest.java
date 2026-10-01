package com.esibuy.esibuy_backend.controlador;

import com.esibuy.esibuy_backend.configuracion.FiltroLimitePeticionesRegistro;
import com.esibuy.esibuy_backend.dto.RespuestaRegistroDTO;
import com.esibuy.esibuy_backend.seguridad.ConfiguracionSeguridad;
import com.esibuy.esibuy_backend.servicio.ServicioRegistro;
import com.esibuy.esibuy_backend.util.ConstructorSolicitudCliente;
import com.esibuy.esibuy_backend.util.ConstructorSolicitudVendedor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Limite de peticiones por IP en el registro (decision D10): al superarlo, 429 con Retry-After.
 */
@WebMvcTest(ControladorAuth.class)
@Import(ConfiguracionSeguridad.class)
class LimitePeticionesRegistroTest {

    private static final String RUTA_CLIENTE = "/api/auth/registro/cliente";
    private static final String RUTA_VENDEDOR = "/api/auth/registro/vendedor";
    private static final int MAXIMO_PETICIONES = 3;

    private static final String IP_ATACANTE = "10.0.0.1";
    private static final String IP_VISITANTE_LEGITIMO = "10.0.0.2";

    @Autowired
    private WebApplicationContext contexto;
    @Autowired
    private ObjectMapper objectMapper;
    @MockitoBean
    private ServicioRegistro servicioRegistro;

    private MockMvc mockMvc;
    private String cuerpoCliente;
    private String cuerpoVendedor;

    @BeforeEach
    void prepararFiltroYServicio() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(contexto)
                .addFilters(new FiltroLimitePeticionesRegistro(MAXIMO_PETICIONES, Duration.ofMinutes(1)))
                .apply(springSecurity())
                .build();
        when(servicioRegistro.registrarCliente(any())).thenReturn(
                new RespuestaRegistroDTO("id-1", "ana@ejemplo.es", "Ana", "Cuenta creada"));
        when(servicioRegistro.registrarVendedor(any())).thenReturn(
                new RespuestaRegistroDTO("id-2", "luis@tienda.es", "Luis", "Cuenta creada"));
        cuerpoCliente = objectMapper.writeValueAsString(ConstructorSolicitudCliente.unaSolicitudValida().construir());
        cuerpoVendedor = objectMapper.writeValueAsString(ConstructorSolicitudVendedor.unaSolicitudValida().construir());
    }

    private MvcResult registrarDesde(String ip, String ruta, String cuerpo) throws Exception {
        return mockMvc.perform(post(ruta)
                .contentType(MediaType.APPLICATION_JSON)
                .content(cuerpo)
                .with(peticion -> {
                    peticion.setRemoteAddr(ip);
                    return peticion;
                })).andReturn();
    }

    @Test
    void registrar_superadoElLimiteDesdeUnaIp_respondeConTooManyRequestsYRetryAfter() throws Exception { // CP-SEG-13
        // Given: la IP agota las peticiones permitidas
        for (int i = 0; i < MAXIMO_PETICIONES; i++) {
            assertThat(registrarDesde(IP_ATACANTE, RUTA_CLIENTE, cuerpoCliente).getResponse().getStatus())
                    .isEqualTo(201);
        }

        // When
        MvcResult resultado = registrarDesde(IP_ATACANTE, RUTA_CLIENTE, cuerpoCliente);

        // Then
        assertThat(resultado.getResponse().getStatus()).isEqualTo(429);
        String retryAfter = resultado.getResponse().getHeader("Retry-After");
        assertThat(retryAfter).isNotNull();
        assertThat(Long.parseLong(retryAfter)).isPositive();
    }

    @Test
    void registrar_superadoElLimiteDesdeUnaIp_otraIpNoSeVeAfectada() throws Exception { // CP-SEG-13
        // Given
        for (int i = 0; i <= MAXIMO_PETICIONES; i++) {
            registrarDesde(IP_ATACANTE, RUTA_CLIENTE, cuerpoCliente);
        }

        // When
        MvcResult resultado = registrarDesde(IP_VISITANTE_LEGITIMO, RUTA_CLIENTE, cuerpoCliente);

        // Then
        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void registrar_clienteYVendedorDesdeLaMismaIp_comparteElMismoContador() throws Exception { // CP-SEG-13
        // Given: dos peticiones de cliente y una de vendedor agotan el limite de 3
        registrarDesde(IP_ATACANTE, RUTA_CLIENTE, cuerpoCliente);
        registrarDesde(IP_ATACANTE, RUTA_CLIENTE, cuerpoCliente);
        registrarDesde(IP_ATACANTE, RUTA_VENDEDOR, cuerpoVendedor);

        // When
        MvcResult resultado = registrarDesde(IP_ATACANTE, RUTA_VENDEDOR, cuerpoVendedor);

        // Then
        assertThat(resultado.getResponse().getStatus()).isEqualTo(429);
    }
}