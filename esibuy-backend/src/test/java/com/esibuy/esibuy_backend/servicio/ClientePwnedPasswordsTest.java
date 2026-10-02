package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Protocolo de Pwned Passwords contra un servidor simulado (sin red).
 *
 * SHA-1("password") = 5BAA6 1E4C9B93F3F0682250B6CF8331B7EE68FD8 (prefijo enviado / sufijo comparado en local).
 */
class ClientePwnedPasswordsTest {

    private static final String URL_BASE = "https://pwned.test";
    private static final String URL_RANGO_PASSWORD = URL_BASE + "/range/5BAA6";
    private static final String SUFIJO_PASSWORD = "1E4C9B93F3F0682250B6CF8331B7EE68FD8";

    private MockRestServiceServer servidor;
    private ClientePwnedPasswords cliente;

    @BeforeEach
    void prepararServidorSimulado() {
        RestClient.Builder builder = RestClient.builder();
        servidor = MockRestServiceServer.bindTo(builder).build();
        cliente = new ClientePwnedPasswords(builder, URL_BASE);
    }

    private void responder(String cuerpo) {
        servidor.expect(requestTo(URL_RANGO_PASSWORD))
                .andExpect(header("Add-Padding", "true"))
                .andRespond(withSuccess(cuerpo, MediaType.TEXT_PLAIN));
    }

    @Test
    void estaFiltrada_sufijoEnLaRespuesta_estaFiltradaYSoloSeEnviaElPrefijo() {
        // Given
        responder("0018A45C4D1DEF81644B54AB7F969B88D65:1\r\n" + SUFIJO_PASSWORD + ":9659365\r\n");

        // When
        boolean filtrada = cliente.estaFiltrada("password");

        // Then: la peticion solo llevaba los 5 primeros caracteres del hash
        assertThat(filtrada).isTrue();
        servidor.verify();
    }

    @Test
    void estaFiltrada_sufijoEnMinusculas_tambienCoincide() {
        // Given
        responder(SUFIJO_PASSWORD.toLowerCase() + ":3\r\n");

        // When / Then
        assertThat(cliente.estaFiltrada("password")).isTrue();
    }

    @Test
    void estaFiltrada_sufijoQueNoAparece_noEstaFiltrada() {
        // Given
        responder("0018A45C4D1DEF81644B54AB7F969B88D65:1\r\n00D4F6E8FA6EECAD2A3AA415EEC418D38EC:2\r\n");

        // When / Then
        assertThat(cliente.estaFiltrada("password")).isFalse();
    }

    @Test
    void estaFiltrada_sufijoSoloComoRelleno_noEstaFiltrada() {
        // Given: con Add-Padding, las entradas de relleno traen 0 apariciones
        responder(SUFIJO_PASSWORD + ":0\r\n");

        // When / Then
        assertThat(cliente.estaFiltrada("password")).isFalse();
    }

    @Test
    void estaFiltrada_servicioConError_lanzaExcepcion() {
        // Given
        servidor.expect(requestTo(URL_RANGO_PASSWORD)).andRespond(withServerError());

        // When / Then: decidir que hacer le corresponde al diccionario compuesto
        assertThrows(RestClientException.class, () -> cliente.estaFiltrada("password"));
    }
}
