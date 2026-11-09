package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Validacion del mensaje de correo (HdU recuperacion de contrasena, fase 0).
 * Casos: CP-COR-01 a CP-COR-06.
 *
 * El mensaje es la ultima barrera antes del servidor SMTP: aqui se impide la inyeccion de cabeceras (saltos de linea
 * en destinatario o asunto, que permitirian anadir destinatarios ocultos) y que el cuerpo, que contiene el enlace de
 * recuperacion, acabe escrito en un log por un toString.
 *
 * Terminada: ya no lleva la etiqueta pendiente-recuperacion y entra en la regresion.
 */
class MensajeCorreoTest {

    private static final String DESTINATARIO = "ana@ejemplo.es";
    private static final String ASUNTO = "Recuperación de contraseña";
    private static final String CUERPO = "Hola";

    @Test
    void crear_datosValidos_conservaLosTresCampos() { // CP-COR-01
        // When
        MensajeCorreo mensaje = new MensajeCorreo(DESTINATARIO, ASUNTO, CUERPO);

        // Then
        assertThat(mensaje.destinatario()).isEqualTo(DESTINATARIO);
        assertThat(mensaje.asunto()).isEqualTo(ASUNTO);
        assertThat(mensaje.cuerpo()).isEqualTo(CUERPO);
    }

    @ParameterizedTest(name = "destinatario con salto de linea: {index}")
    @ValueSource(strings = {
            "ana@ejemplo.es\r\nBcc: espia@malo.es",
            "ana@ejemplo.es\nBcc: espia@malo.es",
            "ana@ejemplo.es\rBcc: espia@malo.es",
            "\r\nana@ejemplo.es" })
    void crear_destinatarioConSaltosDeLinea_seRechazaPorInyeccionDeCabeceras(String destinatario) { // CP-COR-02
        assertThrows(IllegalArgumentException.class, () -> new MensajeCorreo(destinatario, ASUNTO, CUERPO));
    }

    @ParameterizedTest(name = "asunto con salto de linea: {index}")
    @ValueSource(strings = {
            "Hola\r\nBcc: espia@malo.es",
            "Hola\nTo: espia@malo.es",
            "Hola\rBcc: espia@malo.es" })
    void crear_asuntoConSaltosDeLinea_seRechazaPorInyeccionDeCabeceras(String asunto) { // CP-COR-03
        assertThrows(IllegalArgumentException.class, () -> new MensajeCorreo(DESTINATARIO, asunto, CUERPO));
    }

    @ParameterizedTest(name = "destinatario vacio o nulo: [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = { " ", "   " })
    void crear_destinatarioVacio_seRechaza(String destinatario) { // CP-COR-04
        assertThrows(IllegalArgumentException.class, () -> new MensajeCorreo(destinatario, ASUNTO, CUERPO));
    }

    @ParameterizedTest(name = "asunto vacio o nulo: [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = { " " })
    void crear_asuntoVacio_seRechaza(String asunto) { // CP-COR-04
        assertThrows(IllegalArgumentException.class, () -> new MensajeCorreo(DESTINATARIO, asunto, CUERPO));
    }

    @ParameterizedTest(name = "cuerpo vacio o nulo: [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = { " " })
    void crear_cuerpoVacio_seRechaza(String cuerpo) { // CP-COR-04
        assertThrows(IllegalArgumentException.class, () -> new MensajeCorreo(DESTINATARIO, ASUNTO, cuerpo));
    }

    @ParameterizedTest(name = "destinatario no valido o multiple: {0}")
    @ValueSource(strings = {
            "sin-arroba",
            "ana@",
            "@ejemplo.es",
            "ana@ejemplo.es, luis@ejemplo.es",
            "ana@ejemplo.es;luis@ejemplo.es",
            "Ana <ana@ejemplo.es>",
            "ana @ejemplo.es",
            "\"ana\"@ejemplo.es" })
    void crear_destinatarioQueNoEsUnaUnicaDireccion_seRechaza(String destinatario) { // CP-COR-05
        assertThrows(IllegalArgumentException.class, () -> new MensajeCorreo(destinatario, ASUNTO, CUERPO));
    }

    @Test
    void toString_nuncaIncluyeElCuerpo() { // CP-COR-06
        // Given: el cuerpo lleva el enlace con el token de recuperacion
        MensajeCorreo mensaje = new MensajeCorreo(DESTINATARIO, ASUNTO, "https://esibuy.es/r#token=TOKEN-SECRETO-123");

        // When
        String texto = mensaje.toString();

        // Then: si alguien registra el objeto, el token no queda en el log
        assertThat(texto).doesNotContain("TOKEN-SECRETO-123", "https://esibuy.es");
    }
}
