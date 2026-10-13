package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/**
 * Pausador real del modo adaptativo del limitador de login. El resto de pruebas lo sustituyen por un mock; aquí se
 * comprueba la implementación con esperas mínimas.
 *
 * Clase añadida en la fase 6, al conectar el limitador como bean de Spring.
 */
class PausadorRealTest {

    private final PausadorReal pausador = new PausadorReal();

    @Test
    void pausar_duracionCorta_esperaAlMenosEsaDuracion() {
        // Given
        long inicio = System.nanoTime();

        // When
        pausador.pausar(Duration.ofMillis(50));

        // Then
        assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isGreaterThanOrEqualTo(Duration.ofMillis(45));
    }

    @Test
    void pausar_hiloInterrumpido_vuelveEnseguidaYConservaLaMarcaDeInterrupcion() {
        // Given: un hilo ya interrumpido y una pausa que de otro modo duraría diez segundos
        Thread.currentThread().interrupt();
        long inicio = System.nanoTime();

        try {
            // When
            pausador.pausar(Duration.ofSeconds(10));

            // Then
            assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(Duration.ofSeconds(5));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            // Se limpia la marca para no afectar a otras pruebas
            Thread.interrupted();
        }
    }
}
