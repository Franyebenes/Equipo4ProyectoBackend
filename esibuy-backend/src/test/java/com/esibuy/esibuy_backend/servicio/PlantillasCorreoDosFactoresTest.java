package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.esibuy.esibuy_backend.util.PropiedadesCorreoPrueba;

/**
 * Textos de los tres correos de aviso del segundo factor (HdU 2FA, fase 2). Casos: CP-2FA-234 a CP-2FA-241.
 *
 * Son avisos de seguridad para que la persona se entere de un cambio en su cuenta: 2FA activado, desactivado y codigos de
 * recuperacion regenerados. Decisiones que fijan: texto plano (sin HTML), sin enlaces (un aviso con
 * un enlace es lo que usa el phishing, y no hace falta), sin ningun secreto, codigo ni cifra sensible, con un asunto
 * distinto cada uno y siempre con la advertencia de que, si no ha sido la persona, cambie su contrasena.
 *
 * Terminada: ya no lleva la etiqueta pendiente-2fa y entra en la regresion.
 */
class PlantillasCorreoDosFactoresTest {

    private static final String DESTINATARIO = "ana@ejemplo.es";

    private final PlantillasCorreo plantillas = new PlantillasCorreo(PropiedadesCorreoPrueba.remoto().construir());

    private List<MensajeCorreo> todos() {
        return List.of(plantillas.dosFactoresActivado(DESTINATARIO), plantillas.dosFactoresDesactivado(DESTINATARIO),
                plantillas.codigosRecuperacionRegenerados(DESTINATARIO));
    }

    @Test
    void todos_vanDirigidosAlDestinatarioConAsuntoDeUnaLineaYTextoPlano() { // CP-2FA-234
        assertThat(todos()).allSatisfy(correo -> {
            assertThat(correo.destinatario()).isEqualTo(DESTINATARIO);
            assertThat(correo.asunto()).isNotBlank().doesNotContain("\n", "\r");
            // Texto plano: sin etiquetas HTML
            assertThat(correo.cuerpo()).doesNotContain("<", ">");
        });
    }

    @Test
    void todos_tienenUnAsuntoDistinto() { // CP-2FA-235
        assertThat(todos()).extracting(MensajeCorreo::asunto).doesNotHaveDuplicates();
    }

    @Test
    void todos_noLlevanEnlacesNiSignosDeInterrogacion() { // CP-2FA-236
        assertThat(todos()).allSatisfy(correo -> {
            assertThat(correo.cuerpo()).doesNotContainIgnoringCase("http").doesNotContain("://", "www.", "?");
            assertThat(correo.asunto()).doesNotContainIgnoringCase("http").doesNotContain("?");
        });
    }

    @Test
    void todos_avisanDeQueSiNoHaSidoLaPersonaCambieLaContrasena() { // CP-2FA-237
        assertThat(todos()).allSatisfy(correo -> assertThat(correo.cuerpo())
                .containsIgnoringCase("si no has sido tú").containsIgnoringCase("contraseña"));
    }

    @Test
    void activado_diceQueSeHaActivadoLaVerificacionEnDosPasos() { // CP-2FA-238
        MensajeCorreo correo = plantillas.dosFactoresActivado(DESTINATARIO);

        assertThat(correo.asunto()).containsIgnoringCase("dos pasos").containsIgnoringCase("activ");
        assertThat(correo.cuerpo()).containsIgnoringCase("activad");
    }

    @Test
    void desactivado_diceQueSeHaDesactivadoYQueLaCuentaQuedaMenosProtegida() { // CP-2FA-239
        MensajeCorreo correo = plantillas.dosFactoresDesactivado(DESTINATARIO);

        assertThat(correo.asunto()).containsIgnoringCase("dos pasos").containsIgnoringCase("desactiv");
        assertThat(correo.cuerpo()).containsIgnoringCase("desactivad");
    }

    @Test
    void regenerados_dicenQueLosCodigosAnterioresYaNoSirven() { // CP-2FA-240
        MensajeCorreo correo = plantillas.codigosRecuperacionRegenerados(DESTINATARIO);

        assertThat(correo.asunto()).containsIgnoringCase("recuperación");
        assertThat(correo.cuerpo()).containsIgnoringCase("anteriores").containsIgnoringCase("dejado de");
    }

    @ParameterizedTest(name = "destinatario \"{0}\"")
    @NullAndEmptySource
    @ValueSource(strings = { "no-es-un-correo", "a@b.es, c@d.es", "a@b.es\nBcc: x@y.es" })
    void destinatarioInvalido_lanzaExcepcion(String destinatario) { // CP-2FA-241
        // La ultima barrera es MensajeCorreo: rechaza varios destinatarios y la inyeccion de cabeceras
        assertThrows(IllegalArgumentException.class, () -> plantillas.dosFactoresActivado(destinatario));
        assertThrows(IllegalArgumentException.class, () -> plantillas.dosFactoresDesactivado(destinatario));
        assertThrows(IllegalArgumentException.class, () -> plantillas.codigosRecuperacionRegenerados(destinatario));
    }
}
