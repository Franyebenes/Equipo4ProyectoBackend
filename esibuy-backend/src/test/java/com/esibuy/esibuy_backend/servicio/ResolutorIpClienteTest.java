package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Cómo se decide la IP del cliente para los límites por IP.
 *
 * Orden TDD (plan de pruebas, sección 8): paso 2.
 * Casos: CP-BLQ-16.
 * Colaboradores: HttpServletRequest simulado (MockHttpServletRequest) y la lista de proxies de confianza
 * configurada.
 *
 * Regla que fijan estas pruebas: X-Forwarded-For solo se tiene en cuenta si la conexión viene de un proxy de
 * confianza. En ese caso se toma la dirección más a la derecha que no sea de un proxy de confianza (la que ha
 * visto de verdad el primer proxy), porque las de la izquierda las puede escribir el propio cliente. Si la
 * cabecera falta o no tiene una IP con forma válida, se usa la IP de la conexión.
 *
 * Desarrollada con el ciclo Red-Green-Refactor: primero el test (Arrange, Act, Assert), después el código
 * mínimo que lo hace pasar. Terminada: ya no lleva la etiqueta pendiente-login y entra en la regresión.
 */
class ResolutorIpClienteTest {

    private static final String PROXY_CONFIABLE = "10.0.0.1";
    private static final String OTRO_PROXY_CONFIABLE = "10.0.0.2";
    private static final String IP_CLIENTE = "203.0.113.50";
    private static final String IP_FALSIFICADA = "198.51.100.9";
    private static final String CABECERA = "X-Forwarded-For";

    private final ResolutorIpCliente resolutor =
            new ResolutorIpCliente(List.of(PROXY_CONFIABLE, OTRO_PROXY_CONFIABLE));

    private static MockHttpServletRequest peticionDesde(String ipConexion, String reenviadaPor) {
        MockHttpServletRequest peticion = new MockHttpServletRequest();
        peticion.setRemoteAddr(ipConexion);
        if (reenviadaPor != null) {
            peticion.addHeader(CABECERA, reenviadaPor);
        }
        return peticion;
    }

    @Test
    void resolver_cabeceraXForwardedForDeConexionNoConfiable_seIgnoraYUsaLaIpDeLaConexion() { // CP-BLQ-16
        // Given: una conexión directa que no es un proxy de confianza e intenta fingir otra IP
        MockHttpServletRequest peticion = peticionDesde("192.0.2.77", IP_FALSIFICADA);

        // When
        String ip = resolutor.resolver(peticion);

        // Then
        assertThat(ip).isEqualTo("192.0.2.77");
    }

    @Test
    void resolver_cabeceraDeProxyDeConfianza_usaLaIpDelCliente() { // CP-BLQ-16
        // Given: el proxy de confianza informa de la IP del cliente
        MockHttpServletRequest peticion = peticionDesde(PROXY_CONFIABLE, IP_CLIENTE);

        // When
        String ip = resolutor.resolver(peticion);

        // Then
        assertThat(ip).isEqualTo(IP_CLIENTE);
    }

    @Test
    void resolver_cadenaDeProxiesConIpFalsificadaPorElCliente_usaLaAnteriorAlProxyDeConfianza() { // CP-BLQ-16
        // Given: el cliente escribió una IP falsa al principio; los proxies de confianza añadieron la real y la suya
        MockHttpServletRequest peticion = peticionDesde(PROXY_CONFIABLE,
                IP_FALSIFICADA + ", " + IP_CLIENTE + ", " + OTRO_PROXY_CONFIABLE);

        // When
        String ip = resolutor.resolver(peticion);

        // Then: la más a la derecha que no es de un proxy de confianza
        assertThat(ip).isEqualTo(IP_CLIENTE);
    }

    @Test
    void resolver_proxyDeConfianzaSinCabecera_usaLaIpDeLaConexion() { // CP-BLQ-16
        // Given
        MockHttpServletRequest peticion = peticionDesde(PROXY_CONFIABLE, null);

        // When
        String ip = resolutor.resolver(peticion);

        // Then
        assertThat(ip).isEqualTo(PROXY_CONFIABLE);
    }

    @ParameterizedTest(name = "cabecera no válida: [{0}]")
    @ValueSource(strings = {"", "   ", "no-es-una-ip", "<script>alert(1)</script>", "203.0.113.50\r\nX-Falso: 1"})
    void resolver_proxyDeConfianzaConCabeceraSinIpValida_usaLaIpDeLaConexion( // CP-BLQ-16
            String cabecera) {
        // Given: un valor que no es una IP (vacío, texto libre, intento de inyección en logs)
        MockHttpServletRequest peticion = peticionDesde(PROXY_CONFIABLE, cabecera);

        // When
        String ip = resolutor.resolver(peticion);

        // Then: nunca se propaga texto arbitrario
        assertThat(ip).isEqualTo(PROXY_CONFIABLE);
    }

    @Test
    void resolver_sinProxiesConfiguradosComoDeConfianza_siempreUsaLaIpDeLaConexion() { // CP-BLQ-16
        // Given: ningún proxy de confianza configurado
        ResolutorIpCliente sinProxies = new ResolutorIpCliente(List.of());
        MockHttpServletRequest peticion = peticionDesde(PROXY_CONFIABLE, IP_CLIENTE);

        // When
        String ip = sinProxies.resolver(peticion);

        // Then
        assertThat(ip).isEqualTo(PROXY_CONFIABLE);
    }
}
