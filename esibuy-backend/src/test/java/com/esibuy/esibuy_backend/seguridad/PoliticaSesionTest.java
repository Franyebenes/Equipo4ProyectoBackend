package com.esibuy.esibuy_backend.seguridad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpSession;

import com.esibuy.esibuy_backend.modelo.Rol;

/**
 * Caducidad absoluta de la sesión según el rol.
 *
 * Orden TDD (plan de pruebas, sección 8): paso 5.
 * Casos: CP-SES-06.
 * Colaboradores: MockHttpSession; el instante actual se pasa como parámetro (equivale a un Clock fijo).
 *
 * Reglas que fijan estas pruebas: inactividad máxima de 20 min (CLIENTE y PREMIUM) o 15 min (VENDEDOR y ADMIN);
 * caducidad absoluta de 8 h o 6 h, contada desde el inicio de la sesión; una sesión caduca justo al cumplirse el
 * límite aunque haya actividad reciente; una sesión sin instante de inicio o sin rol se considera caducada (falla
 * cerrado).
 *
 * Terminada: ya no lleva la etiqueta pendiente-login y entra en la regresión.
 */
class PoliticaSesionTest {

    private static final Instant AHORA = Instant.parse("2026-10-06T10:00:00Z");

    private final PoliticaSesion politica = new PoliticaSesion();

    static Stream<Arguments> limitesPorRol() {
        return Stream.of(
                arguments(Rol.CLIENTE, Duration.ofMinutes(20), Duration.ofHours(8)),
                arguments(Rol.PREMIUM, Duration.ofMinutes(20), Duration.ofHours(8)),
                arguments(Rol.VENDEDOR, Duration.ofMinutes(15), Duration.ofHours(6)),
                arguments(Rol.ADMIN, Duration.ofMinutes(15), Duration.ofHours(6)));
    }

    private static MockHttpSession sesionIniciadaHace(Rol rol, Duration antiguedad) {
        MockHttpSession sesion = new MockHttpSession();
        sesion.setAttribute(PoliticaSesion.ATRIBUTO_INICIO, AHORA.minus(antiguedad));
        sesion.setAttribute(PoliticaSesion.ATRIBUTO_ROL, rol);
        return sesion;
    }

    @ParameterizedTest(name = "{0}: inactividad {1}, caducidad absoluta {2}")
    @MethodSource("limitesPorRol")
    void limitesDelRol_cadaRol_tieneSuInactividadMaximaYSuCaducidadAbsoluta( // CP-SES-06
            Rol rol, Duration inactividad, Duration absoluta) {
        // When / Then
        assertThat(politica.inactividadMaxima(rol)).isEqualTo(inactividad);
        assertThat(politica.duracionAbsoluta(rol)).isEqualTo(absoluta);
    }

    @ParameterizedTest(name = "{0}: caducidad absoluta {2}")
    @MethodSource("limitesPorRol")
    void haCaducado_limiteAbsolutoPorRol_caducaExactamenteEnElLimiteAunqueHayaActividad( // CP-SES-06
            Rol rol, Duration inactividad, Duration absoluta) {
        // Given: sesiones con actividad reciente (cada una acaba de ser usada)
        MockHttpSession aUnSegundoDelLimite = sesionIniciadaHace(rol, absoluta.minusSeconds(1));
        MockHttpSession justoEnElLimite = sesionIniciadaHace(rol, absoluta);
        MockHttpSession pasadoElLimite = sesionIniciadaHace(rol, absoluta.plusSeconds(1));
        aUnSegundoDelLimite.access();
        justoEnElLimite.access();
        pasadoElLimite.access();

        // When / Then: un segundo antes sigue vigente; en el límite y después, caducada
        assertThat(politica.haCaducado(aUnSegundoDelLimite, AHORA)).isFalse();
        assertThat(politica.haCaducado(justoEnElLimite, AHORA)).isTrue();
        assertThat(politica.haCaducado(pasadoElLimite, AHORA)).isTrue();
    }

    @ParameterizedTest(name = "{0}: sesión recién iniciada")
    @EnumSource(Rol.class)
    void haCaducado_sesionRecienIniciada_noHaCaducado( // CP-SES-06
            Rol rol) {
        assertThat(politica.haCaducado(sesionIniciadaHace(rol, Duration.ZERO), AHORA)).isFalse();
    }

    @Test
    void haCaducado_sesionSinInstanteDeInicio_seConsideraCaducada() { // CP-SES-06
        // Given: una sesión con rol pero sin instante de inicio
        MockHttpSession sesion = new MockHttpSession();
        sesion.setAttribute(PoliticaSesion.ATRIBUTO_ROL, Rol.CLIENTE);

        // When / Then: falla cerrado
        assertThat(politica.haCaducado(sesion, AHORA)).isTrue();
    }

    @Test
    void haCaducado_sesionSinRol_seConsideraCaducada() { // CP-SES-06
        // Given: una sesión con instante de inicio pero sin rol (no se puede saber su límite)
        MockHttpSession sesion = new MockHttpSession();
        sesion.setAttribute(PoliticaSesion.ATRIBUTO_INICIO, AHORA);

        // When / Then: falla cerrado
        assertThat(politica.haCaducado(sesion, AHORA)).isTrue();
    }
}
