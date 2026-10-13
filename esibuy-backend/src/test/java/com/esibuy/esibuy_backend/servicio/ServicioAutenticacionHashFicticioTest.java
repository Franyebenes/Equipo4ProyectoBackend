package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.esibuy.esibuy_backend.dto.SolicitudLoginDTO;
import com.esibuy.esibuy_backend.excepcion.CredencialesInvalidasException;

/**
 * El hash ficticio (el que se verifica cuando el correo no existe, para igualar los tiempos) se puede calcular al
 * arrancar, de modo que ninguna petición real pague el coste de crearlo.
 *
 * Clase añadida porque ServicioAutenticacionRechazoTest pertenece a una fase cerrada.
 */
class ServicioAutenticacionHashFicticioTest extends ServicioAutenticacionBaseTest {

    @Test
    void precalcularHashFicticio_antesDeLaPrimeraPeticion_loCreaUnaSolaVezYLoUsaDespues() {
        // Given: el hash se calcula por adelantado
        servicio.precalcularHashFicticio();
        verify(codificador, times(1)).encode(anyString());

        // When: dos peticiones con un correo que no existe
        for (int i = 0; i < 2; i++) {
            assertThrows(CredencialesInvalidasException.class,
                    () -> servicio.autenticar(new SolicitudLoginDTO(EMAIL, CONTRASENA), CONTEXTO));
        }

        // Then: se verifica contra ese hash, y no se vuelve a crear ninguno
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(codificador, times(2)).matches(eq(CONTRASENA), hash.capture());
        assertThat(hash.getAllValues()).containsOnly(HASH_FICTICIO);
        verify(codificador, times(1)).encode(anyString());
    }

    @Test
    void precalcularHashFicticio_llamadoDosVeces_noLoRecalcula() {
        // When
        servicio.precalcularHashFicticio();
        servicio.precalcularHashFicticio();

        // Then
        verify(codificador, times(1)).encode(anyString());
    }
}
