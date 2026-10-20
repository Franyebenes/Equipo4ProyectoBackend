package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Validacion de la configuracion del 2FA (HdU 2FA, fase 0). Casos: CP-2FA-66 a CP-2FA-78.
 *
 * Una configuracion absurda (clave de tamano incorrecto, cero intentos, ventanas vacias, un emisor con dos puntos que
 * rompe la etiqueta del QR) no debe poder arrancar: dejaria el 2FA inutilizable o sin proteccion sin que nadie lo
 * note. Las claves de cifrado se escriben en Base64 y deben medir exactamente 32 bytes (AES-256).
 *
 * Terminada: ya no lleva la etiqueta pendiente-2fa y entra en la regresion.
 */
class PropiedadesDosFactoresTest {

    private static final Duration MINUTO = Duration.ofMinutes(1);
    private static final Duration HORA = Duration.ofHours(1);

    private static byte[] bytesDeClave(int semilla) {
        byte[] clave = new byte[32];
        for (int i = 0; i < clave.length; i++) {
            clave[i] = (byte) (semilla + i);
        }
        return clave;
    }

    private static String claveBase64(int semilla) {
        return Base64.getEncoder().encodeToString(bytesDeClave(semilla));
    }

    private static Map<Integer, String> unaClave() {
        return Map.of(1, claveBase64(1));
    }

    /** Valores acordados; los parametros que cada test cambia se pasan a mano al constructor. */
    private static PropiedadesDosFactores validas() {
        return new PropiedadesDosFactores("ESIBuy", unaClave(), 1, Duration.ofMinutes(10), Duration.ofMinutes(5), 5, 10,
                Duration.ofMinutes(15), 30, HORA, 10);
    }

    // ---------------------------------------------------------------- valores acordados

    @Test
    void crear_valoresAcordados_esValida() { // CP-2FA-66
        PropiedadesDosFactores propiedades = validas();

        assertThat(propiedades.emisor()).isEqualTo("ESIBuy");
        assertThat(propiedades.versionActiva()).isEqualTo(1);
        assertThat(propiedades.caducidadActivacion()).isEqualTo(Duration.ofMinutes(10));
        assertThat(propiedades.caducidadDesafio()).isEqualTo(Duration.ofMinutes(5));
        assertThat(propiedades.maxIntentosDesafio()).isEqualTo(5);
        assertThat(propiedades.maxFallosCuenta()).isEqualTo(10);
        assertThat(propiedades.ventanaCuenta()).isEqualTo(Duration.ofMinutes(15));
        assertThat(propiedades.maxPorIp()).isEqualTo(30);
        assertThat(propiedades.ventanaIp()).isEqualTo(HORA);
        assertThat(propiedades.codigosRecuperacion()).isEqualTo(10);
    }

    // ---------------------------------------------------------------- emisor

    @ParameterizedTest(name = "\"{0}\"")
    @NullAndEmptySource
    @ValueSource(strings = { " ", "ESI:Buy", "ESI\nBuy", "ESI\u0000Buy",
            "EmisorDemasiadoLargoParaUnaEtiquetaDeAplicacionAutenticadora" })
    void crear_emisorInvalido_seRechaza(String emisor) { // CP-2FA-67
        // Vacio, con dos puntos (separan emisor y cuenta), con caracteres de control o de mas de 40 caracteres
        assertThrows(IllegalArgumentException.class, () -> new PropiedadesDosFactores(emisor, unaClave(), 1,
                Duration.ofMinutes(10), Duration.ofMinutes(5), 5, 10, Duration.ofMinutes(15), 30, HORA, 10));
    }

    // ---------------------------------------------------------------- claves

    @Test
    void crear_sinClaves_seRechaza() { // CP-2FA-68
        assertThrows(IllegalArgumentException.class, () -> new PropiedadesDosFactores("ESIBuy", null, 1,
                Duration.ofMinutes(10), Duration.ofMinutes(5), 5, 10, Duration.ofMinutes(15), 30, HORA, 10));
        assertThrows(IllegalArgumentException.class, () -> new PropiedadesDosFactores("ESIBuy", Map.of(), 1,
                Duration.ofMinutes(10), Duration.ofMinutes(5), 5, 10, Duration.ofMinutes(15), 30, HORA, 10));
    }

    @ParameterizedTest(name = "clave \"{0}\"")
    @NullAndEmptySource
    @ValueSource(strings = {
            " ",
            "no-es-base64!!!",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAA=", // 20 bytes
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" // 42 bytes
    })
    void crear_claveQueNoSon32BytesEnBase64_seRechaza(String clave) { // CP-2FA-69
        Map<Integer, String> claves = new HashMap<>();
        claves.put(1, clave);

        assertThrows(IllegalArgumentException.class, () -> new PropiedadesDosFactores("ESIBuy", claves, 1,
                Duration.ofMinutes(10), Duration.ofMinutes(5), 5, 10, Duration.ofMinutes(15), 30, HORA, 10));
    }

    @Test
    void crear_unaClaveInvalidaEntreVarias_seRechaza() { // CP-2FA-69
        Map<Integer, String> claves = new HashMap<>();
        claves.put(1, claveBase64(1));
        claves.put(2, "AAAA");

        assertThrows(IllegalArgumentException.class, () -> new PropiedadesDosFactores("ESIBuy", claves, 1,
                Duration.ofMinutes(10), Duration.ofMinutes(5), 5, 10, Duration.ofMinutes(15), 30, HORA, 10));
    }

    @ParameterizedTest(name = "version {0}")
    @ValueSource(ints = { 0, -1, -7 })
    void crear_versionDeClaveMenorQueUno_seRechaza(int version) { // CP-2FA-70
        assertThrows(IllegalArgumentException.class, () -> new PropiedadesDosFactores("ESIBuy",
                Map.of(version, claveBase64(1)), version, Duration.ofMinutes(10), Duration.ofMinutes(5), 5, 10,
                Duration.ofMinutes(15), 30, HORA, 10));
    }

    @Test
    void crear_versionActivaQueNoEstaEntreLasClaves_seRechaza() { // CP-2FA-70
        assertThrows(IllegalArgumentException.class, () -> new PropiedadesDosFactores("ESIBuy", unaClave(), 2,
                Duration.ofMinutes(10), Duration.ofMinutes(5), 5, 10, Duration.ofMinutes(15), 30, HORA, 10));
    }

    @Test
    void crear_variasClavesConLaActivaEntreEllas_esValida() { // CP-2FA-70
        Map<Integer, String> claves = Map.of(1, claveBase64(1), 2, claveBase64(2));

        PropiedadesDosFactores propiedades = new PropiedadesDosFactores("ESIBuy", claves, 2, Duration.ofMinutes(10),
                Duration.ofMinutes(5), 5, 10, Duration.ofMinutes(15), 30, HORA, 10);

        assertThat(propiedades.versionActiva()).isEqualTo(2);
        assertThat(propiedades.claves()).containsOnlyKeys(1, 2);
    }

    @Test
    void clavesEnBytes_decodificaCadaClave() { // CP-2FA-71
        Map<Integer, String> claves = Map.of(1, claveBase64(1), 2, claveBase64(2));
        PropiedadesDosFactores propiedades = new PropiedadesDosFactores("ESIBuy", claves, 2, Duration.ofMinutes(10),
                Duration.ofMinutes(5), 5, 10, Duration.ofMinutes(15), 30, HORA, 10);

        Map<Integer, byte[]> enBytes = propiedades.clavesEnBytes();

        assertThat(enBytes).containsOnlyKeys(1, 2);
        assertThat(enBytes.get(1)).isEqualTo(bytesDeClave(1));
        assertThat(enBytes.get(2)).isEqualTo(bytesDeClave(2));
    }

    @Test
    void clavesEnBytes_devuelveCopias_cambiarlasNoAlteraLaConfiguracion() { // CP-2FA-71
        PropiedadesDosFactores propiedades = validas();

        Arrays.fill(propiedades.clavesEnBytes().get(1), (byte) 0);

        assertThat(propiedades.clavesEnBytes().get(1)).isEqualTo(bytesDeClave(1));
    }

    @Test
    void crear_copiaElMapaDeClaves_cambiarloDespuesNoAfecta() { // CP-2FA-72
        Map<Integer, String> claves = new HashMap<>();
        claves.put(1, claveBase64(1));
        PropiedadesDosFactores propiedades = new PropiedadesDosFactores("ESIBuy", claves, 1, Duration.ofMinutes(10),
                Duration.ofMinutes(5), 5, 10, Duration.ofMinutes(15), 30, HORA, 10);

        claves.put(2, claveBase64(2));
        claves.remove(1);

        assertThat(propiedades.claves()).containsOnlyKeys(1);
        assertThrows(UnsupportedOperationException.class, () -> propiedades.claves().put(3, claveBase64(3)));
    }

    @Test
    void toString_noMuestraLasClaves() { // CP-2FA-73
        String texto = validas().toString();

        assertThat(texto).doesNotContain(claveBase64(1)).doesNotContain(Arrays.toString(bytesDeClave(1)));
    }

    // ---------------------------------------------------------------- limites y duraciones

    @ParameterizedTest(name = "valor {0}")
    @ValueSource(ints = { 0, -1, -100 })
    void crear_contadoresMenoresQueUno_seRechazan(int valor) { // CP-2FA-74
        assertThrows(IllegalArgumentException.class, () -> new PropiedadesDosFactores("ESIBuy", unaClave(), 1,
                Duration.ofMinutes(10), Duration.ofMinutes(5), valor, 10, Duration.ofMinutes(15), 30, HORA, 10));
        assertThrows(IllegalArgumentException.class, () -> new PropiedadesDosFactores("ESIBuy", unaClave(), 1,
                Duration.ofMinutes(10), Duration.ofMinutes(5), 5, valor, Duration.ofMinutes(15), 30, HORA, 10));
        assertThrows(IllegalArgumentException.class, () -> new PropiedadesDosFactores("ESIBuy", unaClave(), 1,
                Duration.ofMinutes(10), Duration.ofMinutes(5), 5, 10, Duration.ofMinutes(15), valor, HORA, 10));
    }

    @Test
    void crear_duracionesNulasONoPositivas_seRechazan() { // CP-2FA-75
        for (Duration mala : new Duration[] { null, Duration.ZERO, Duration.ofSeconds(-1) }) {
            assertThrows(IllegalArgumentException.class, () -> new PropiedadesDosFactores("ESIBuy", unaClave(), 1, mala,
                    Duration.ofMinutes(5), 5, 10, Duration.ofMinutes(15), 30, HORA, 10), "caducidad de activacion");
            assertThrows(IllegalArgumentException.class, () -> new PropiedadesDosFactores("ESIBuy", unaClave(), 1,
                    Duration.ofMinutes(10), mala, 5, 10, Duration.ofMinutes(15), 30, HORA, 10), "caducidad del desafio");
            assertThrows(IllegalArgumentException.class, () -> new PropiedadesDosFactores("ESIBuy", unaClave(), 1,
                    Duration.ofMinutes(10), Duration.ofMinutes(5), 5, 10, mala, 30, HORA, 10), "ventana de la cuenta");
            assertThrows(IllegalArgumentException.class, () -> new PropiedadesDosFactores("ESIBuy", unaClave(), 1,
                    Duration.ofMinutes(10), Duration.ofMinutes(5), 5, 10, Duration.ofMinutes(15), 30, mala, 10),
                    "ventana de la IP");
        }
    }

    @ParameterizedTest(name = "{0} codigos")
    @ValueSource(ints = { 0, -1, 21, 100 })
    void crear_numeroDeCodigosDeRecuperacionFueraDeRango_seRechaza(int cantidad) { // CP-2FA-76
        // El generador de codigos acepta de 1 a 20: la configuracion no puede pedir otra cosa
        assertThrows(IllegalArgumentException.class, () -> new PropiedadesDosFactores("ESIBuy", unaClave(), 1,
                Duration.ofMinutes(10), Duration.ofMinutes(5), 5, 10, Duration.ofMinutes(15), 30, HORA, cantidad));
    }

    @ParameterizedTest(name = "{0} codigos")
    @ValueSource(ints = { 1, 10, 20 })
    void crear_numeroDeCodigosDeRecuperacionEnRango_esValido(int cantidad) { // CP-2FA-76
        PropiedadesDosFactores propiedades = new PropiedadesDosFactores("ESIBuy", unaClave(), 1,
                Duration.ofMinutes(10), Duration.ofMinutes(5), 5, 10, Duration.ofMinutes(15), 30, HORA, cantidad);

        assertThat(propiedades.codigosRecuperacion()).isEqualTo(cantidad);
    }

    @Test
    void crear_elDesafioNoPuedeDurarMasDeUnaHora() { // CP-2FA-77
        // Un desafio pendiente es una sesion a medias: no debe poder quedarse abierto horas
        assertThrows(IllegalArgumentException.class, () -> new PropiedadesDosFactores("ESIBuy", unaClave(), 1,
                Duration.ofMinutes(10), Duration.ofMinutes(61), 5, 10, Duration.ofMinutes(15), 30, HORA, 10));
        assertThat(new PropiedadesDosFactores("ESIBuy", unaClave(), 1, Duration.ofMinutes(10), HORA, 5, 10,
                Duration.ofMinutes(15), 30, HORA, 10).caducidadDesafio()).isEqualTo(HORA);
    }

    @Test
    void crear_laActivacionNoPuedeDurarMasDeUnaHora() { // CP-2FA-78
        // El secreto pendiente se guarda mientras la persona escanea el QR: se descarta pronto
        assertThrows(IllegalArgumentException.class, () -> new PropiedadesDosFactores("ESIBuy", unaClave(), 1,
                Duration.ofMinutes(61), Duration.ofMinutes(5), 5, 10, Duration.ofMinutes(15), 30, HORA, 10));
        assertThat(new PropiedadesDosFactores("ESIBuy", unaClave(), 1, HORA, MINUTO, 5, 10, Duration.ofMinutes(15), 30,
                HORA, 10).caducidadActivacion()).isEqualTo(HORA);
    }
}
