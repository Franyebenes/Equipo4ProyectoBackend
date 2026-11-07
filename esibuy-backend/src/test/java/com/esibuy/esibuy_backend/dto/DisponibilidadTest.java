package com.esibuy.esibuy_backend.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** HU-19 / HU-21 - Disponibilidad que ve el cliente: agotado, poco stock (menos de 5) o disponible. */
class DisponibilidadTest {

    @ParameterizedTest
    @CsvSource({
            "0, AGOTADO",
            "1, POCO_STOCK",
            "4, POCO_STOCK",
            "5, DISPONIBLE",
            "100, DISPONIBLE"
    })
    void de_segunLasUnidades_devuelveSuDisponibilidad(int stock, Disponibilidad esperada) {
        assertThat(Disponibilidad.de(stock)).isEqualTo(esperada);
    }

    @Test
    void de_sinStockInformado_esAgotado() {
        assertThat(Disponibilidad.de(null)).isEqualTo(Disponibilidad.AGOTADO);
    }
}
