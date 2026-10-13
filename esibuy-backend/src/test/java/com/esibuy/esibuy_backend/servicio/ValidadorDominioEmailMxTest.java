package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.naming.CommunicationException;
import javax.naming.NameNotFoundException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.esibuy.esibuy_backend.excepcion.ServicioNoDisponibleException;

/**
 * Logica de la comprobacion de dominio con un DNS simulado (sin red).
 */
class ValidadorDominioEmailMxTest {

    /** Registros por "dominio/tipo"; los dominios de "inexistentes" responden NXDOMAIN. */
    private final Map<String, List<String>> registros = new HashMap<>();
    private final List<String> inexistentes = new ArrayList<>();
    private final List<String> consultados = new ArrayList<>();

    private final ValidadorDominioEmailMx validador = new ValidadorDominioEmailMx((dominio, tipo) -> {
        consultados.add(dominio);
        if (inexistentes.contains(dominio)) {
            throw new NameNotFoundException(dominio);
        }
        return registros.getOrDefault(dominio + "/" + tipo, List.of());
    });

    @Test
    void tieneDominioValido_dominioConRegistroMx_esValido() {
        // Given
        registros.put("ejemplo.es/MX", List.of("10 mail.ejemplo.es."));

        // When / Then
        assertThat(validador.tieneDominioValido("ana@ejemplo.es")).isTrue();
    }

    @Test
    void tieneDominioValido_nullMx_noEsValido() {
        // Given: RFC 7505, el dominio declara que no acepta correo
        registros.put("sin-correo.es/MX", List.of("0 ."));

        // When / Then
        assertThat(validador.tieneDominioValido("ana@sin-correo.es")).isFalse();
    }

    @Test
    void tieneDominioValido_sinMxPeroConDireccionA_esValidoPorMxImplicito() {
        // Given: RFC 5321, la direccion del propio dominio actua como MX
        registros.put("solo-a.es/A", List.of("192.0.2.10"));

        // When / Then
        assertThat(validador.tieneDominioValido("ana@solo-a.es")).isTrue();
    }

    @Test
    void tieneDominioValido_sinMxNiDirecciones_noEsValido() {
        // When / Then
        assertThat(validador.tieneDominioValido("ana@vacio.es")).isFalse();
    }

    @Test
    void tieneDominioValido_dominioInexistente_noEsValido() {
        // Given
        inexistentes.add("no-existe-xyz.es");

        // When / Then
        assertThat(validador.tieneDominioValido("ana@no-existe-xyz.es")).isFalse();
    }

    @Test
    void tieneDominioValido_dnsNoResponde_lanzaServicioNoDisponible() {
        // Given
        ValidadorDominioEmailMx validadorSinDns = new ValidadorDominioEmailMx((dominio, tipo) -> {
            throw new CommunicationException("timeout");
        });

        // When / Then
        assertThrows(ServicioNoDisponibleException.class,
                () -> validadorSinDns.tieneDominioValido("ana@ejemplo.es"));
    }

    @Test
    void tieneDominioValido_dominioConEnie_seConsultaEnPunycode() {
        // Given
        registros.put("xn--espaa-rta.es/MX", List.of("10 mail.xn--espaa-rta.es."));

        // When
        boolean valido = validador.tieneDominioValido("ana@españa.es");

        // Then
        assertThat(valido).isTrue();
        assertThat(consultados).containsOnly("xn--espaa-rta.es");
    }

    @ParameterizedTest(name = "email sin dominio: \"{0}\"")
    @NullAndEmptySource
    @ValueSource(strings = {"sin-arroba", "ana@"})
    void tieneDominioValido_emailSinDominio_noEsValidoYNoConsultaElDns(String email) {
        // When / Then
        assertThat(validador.tieneDominioValido(email)).isFalse();
        assertThat(consultados).isEmpty();
    }
}
