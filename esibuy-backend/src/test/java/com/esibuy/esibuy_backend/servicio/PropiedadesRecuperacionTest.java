package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Validacion de los limites de la recuperacion de contrasena (HdU recuperacion de contrasena, fase 3).
 * Casos: CP-LIM-01 a CP-LIM-05.
 *
 * Una configuracion absurda (cero solicitudes, ventanas vacias, una espera mayor que la ventana) no debe poder arrancar:
 * dejaria la recuperacion inutilizable o sin proteccion sin que nadie lo note.
 *
 * Terminada: ya no lleva la etiqueta pendiente-recuperacion y entra en la regresion.
 */
class PropiedadesRecuperacionTest {

    private static final Duration DIA = Duration.ofHours(24);
    private static final Duration MINUTO = Duration.ofMinutes(1);
    private static final Duration HORA = Duration.ofHours(1);

    @Test
    void crear_valoresAcordados_esValida() { // CP-LIM-01
        PropiedadesRecuperacion propiedades = new PropiedadesRecuperacion(5, DIA, MINUTO, 10, HORA);

        assertThat(propiedades.maxPorCuenta()).isEqualTo(5);
        assertThat(propiedades.ventanaCuenta()).isEqualTo(DIA);
        assertThat(propiedades.cooldown()).isEqualTo(MINUTO);
        assertThat(propiedades.maxPorIp()).isEqualTo(10);
        assertThat(propiedades.ventanaIp()).isEqualTo(HORA);
    }

    @ParameterizedTest(name = "maximo por cuenta {0}")
    @ValueSource(ints = { 0, -1, -100 })
    void crear_maximoPorCuentaMenorQueUno_seRechaza(int maximo) { // CP-LIM-02
        assertThrows(IllegalArgumentException.class, () -> new PropiedadesRecuperacion(maximo, DIA, MINUTO, 10, HORA));
    }

    @ParameterizedTest(name = "maximo por IP {0}")
    @ValueSource(ints = { 0, -1, -100 })
    void crear_maximoPorIpMenorQueUno_seRechaza(int maximo) { // CP-LIM-02
        assertThrows(IllegalArgumentException.class, () -> new PropiedadesRecuperacion(5, DIA, MINUTO, maximo, HORA));
    }

    @Test
    void crear_ventanasYEsperaNoPositivasONulas_seRechazan() { // CP-LIM-03
        assertThrows(IllegalArgumentException.class, () -> new PropiedadesRecuperacion(5, null, MINUTO, 10, HORA));
        assertThrows(IllegalArgumentException.class,
                () -> new PropiedadesRecuperacion(5, Duration.ZERO, MINUTO, 10, HORA));
        assertThrows(IllegalArgumentException.class,
                () -> new PropiedadesRecuperacion(5, Duration.ofHours(-1), MINUTO, 10, HORA));
        assertThrows(IllegalArgumentException.class, () -> new PropiedadesRecuperacion(5, DIA, null, 10, HORA));
        assertThrows(IllegalArgumentException.class,
                () -> new PropiedadesRecuperacion(5, DIA, Duration.ZERO, 10, HORA));
        assertThrows(IllegalArgumentException.class,
                () -> new PropiedadesRecuperacion(5, DIA, Duration.ofSeconds(-5), 10, HORA));
        assertThrows(IllegalArgumentException.class, () -> new PropiedadesRecuperacion(5, DIA, MINUTO, 10, null));
        assertThrows(IllegalArgumentException.class,
                () -> new PropiedadesRecuperacion(5, DIA, MINUTO, 10, Duration.ZERO));
    }

    @Test
    void crear_esperaIgualOMayorQueLaVentanaDeLaCuenta_seRechaza() { // CP-LIM-04
        // Con una espera que cubre toda la ventana, el maximo diario no se alcanzaria nunca: configuracion incoherente
        assertThrows(IllegalArgumentException.class, () -> new PropiedadesRecuperacion(5, HORA, HORA, 10, HORA));
        assertThrows(IllegalArgumentException.class,
                () -> new PropiedadesRecuperacion(5, HORA, Duration.ofHours(2), 10, HORA));
    }

    @Test
    void crear_esperaMuyCortaPeroPositiva_esValida() { // CP-LIM-05
        // Los tests de integracion de la solicitud la bajan a un milisegundo para no estorbarse entre ellos
        assertThat(new PropiedadesRecuperacion(1000, DIA, Duration.ofMillis(1), 1000, HORA).cooldown())
                .isEqualTo(Duration.ofMillis(1));
    }
}
