package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.ResourceAccessException;

import com.esibuy.esibuy_backend.util.CapturadorLogs;

/**
 * Combinacion de las listas locales con Pwned Passwords y comportamiento cuando el servicio falla.
 */
@ExtendWith(MockitoExtension.class)
class DiccionarioContrasenasCompuestoTest {

    private static final String CONTRASENA = "Barcelona2024!";

    @Mock
    private DiccionarioContrasenasLocal local;
    @Mock
    private ClientePwnedPasswords pwnedPasswords;

    private DiccionarioContrasenasCompuesto diccionario(boolean pwnedHabilitado) {
        return new DiccionarioContrasenasCompuesto(local, pwnedPasswords, pwnedHabilitado);
    }

    @Test
    void esComun_delegaEnLaListaLocal() {
        // Given
        when(local.esComun("qwerty123456")).thenReturn(true);

        // When / Then
        assertThat(diccionario(true).esComun("qwerty123456")).isTrue();
    }

    @Test
    void estaFiltrada_enLaListaLocal_noConsultaElServicioExterno() {
        // Given
        when(local.estaFiltrada(CONTRASENA)).thenReturn(true);

        // When
        boolean filtrada = diccionario(true).estaFiltrada(CONTRASENA);

        // Then
        assertThat(filtrada).isTrue();
        verify(pwnedPasswords, never()).estaFiltrada(anyString());
    }

    @Test
    void estaFiltrada_soloEnPwnedPasswords_estaFiltrada() {
        // Given
        when(pwnedPasswords.estaFiltrada(CONTRASENA)).thenReturn(true);

        // When / Then
        assertThat(diccionario(true).estaFiltrada(CONTRASENA)).isTrue();
    }

    @Test
    void estaFiltrada_enNingunSitio_noEstaFiltrada() {
        // When / Then
        assertThat(diccionario(true).estaFiltrada(CONTRASENA)).isFalse();
    }

    @Test
    void estaFiltrada_pwnedPasswordsNoResponde_dejaPasarYLoAvisaEnElLogSinLaContrasena() {
        // Given
        when(pwnedPasswords.estaFiltrada(CONTRASENA)).thenThrow(new ResourceAccessException("Read timed out"));

        // When
        boolean filtrada;
        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            filtrada = diccionario(true).estaFiltrada(CONTRASENA);

            // Then
            assertThat(logs.textosCompletos())
                    .anyMatch(texto -> texto.contains("Pwned Passwords no disponible"))
                    .noneMatch(texto -> texto.contains(CONTRASENA));
        }
        assertThat(filtrada).isFalse();
    }

    @Test
    void estaFiltrada_pwnedPasswordsDeshabilitado_usaSoloLaListaLocal() {
        // When
        boolean filtrada = diccionario(false).estaFiltrada(CONTRASENA);

        // Then
        assertThat(filtrada).isFalse();
        verify(pwnedPasswords, never()).estaFiltrada(anyString());
    }
}
