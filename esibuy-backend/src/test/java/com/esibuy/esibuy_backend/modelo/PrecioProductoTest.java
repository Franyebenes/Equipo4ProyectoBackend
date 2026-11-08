package com.esibuy.esibuy_backend.modelo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Precio que paga cada cliente: todos tienen el descuento general y los premium, ademas, el descuento premium. Los
 * dos se suman: con un 10 % y un 15 % premium, el premium paga un 25 % menos.
 */
class PrecioProductoTest {

    @ParameterizedTest
    @CsvSource(nullValues = "-", value = {
        // base, descuento, premium, paga un cliente estandar, paga un premium
        "100,   -,    -,    100,  100",
        "100,   10,   -,    90,   90",
        "100,   -,    15,   100,  85",
        "100,   10,   15,   90,   75",
        "100,   20,   10,   80,   70",
        "20,    10,   25,   18,   13",
        "100,   60,   40,   40,   0",
    })
    void precioFinal_elPremiumTieneLosDosDescuentosSumados(double base, Double descuento, Double descuentoPremium,
                                                          double estandar, double premium) {
        // Given
        PrecioProducto precio = new PrecioProducto(base, descuento, descuentoPremium);

        // When / Then
        assertThat(precio.precioFinal(false)).isEqualTo(estandar);
        assertThat(precio.precioFinal(true)).isEqualTo(premium);
    }

    @Test
    void descuentoTotal_esLaSumaDeLosDosParaElPremium() {
        // Given: el ejemplo de la HU, un 10 % para todos y un 15 % extra para premium
        PrecioProducto precio = new PrecioProducto(100.0, 10.0, 15.0);

        // When / Then
        assertThat(precio.descuentoTotal(false)).isEqualTo(10.0);
        assertThat(precio.descuentoTotal(true)).isEqualTo(25.0);
    }

    @Test
    void precioFinal_seRedondeaACentimos() {
        // Given: 19,99 € con un 10 % y un 5 % premium son 16,9915 € para el premium
        PrecioProducto precio = new PrecioProducto(19.99, 10.0, 5.0);

        // When / Then
        assertThat(precio.precioFinal(true)).isEqualTo(16.99);
        assertThat(precio.precioFinal(false)).isEqualTo(17.99);
    }

    @Test
    void precioFinal_nuncaEsNegativoAunqueLosDescuentosGuardadosPasenDel100() {
        // Given: datos antiguos que no cumplen la regla del alta (60 % + 50 %)
        PrecioProducto precio = new PrecioProducto(100.0, 60.0, 50.0);

        // When / Then
        assertThat(precio.precioFinal(true)).isZero();
        assertThat(precio.precioFinal(false)).isEqualTo(40.0);
    }
}
