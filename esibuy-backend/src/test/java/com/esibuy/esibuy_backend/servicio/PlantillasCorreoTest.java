package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.esibuy.esibuy_backend.util.PropiedadesCorreoPrueba;

/**
 * Textos de los dos correos de la recuperacion de contrasena (HdU recuperacion de contrasena, fase 0).
 * Casos: CP-COR-60 a CP-COR-69.
 *
 * Decisiones que fijan: el token va en el FRAGMENTO de la URL (tras #), que el navegador nunca envia al servidor ni
 * en la cabecera Referer; el enlace se construye siempre con la URL base de la configuracion (nunca con datos de la
 * peticion); los correos son texto plano; y el aviso de cuenta pendiente no lleva enlace ni token alguno.
 *
 * Terminada: ya no lleva la etiqueta pendiente-recuperacion y entra en la regresion.
 */
class PlantillasCorreoTest {

    private static final String DESTINATARIO = "ana@ejemplo.es";
    private static final String TOKEN = "Zk3-Qw9_Lm2xPa7RtYb4NcV8HdS1JfGe6UoIi0KuXyA";
    private static final Duration VALIDEZ = Duration.ofMinutes(15);

    private final PlantillasCorreo plantillas = new PlantillasCorreo(PropiedadesCorreoPrueba.remoto().construir());

    // ------------------------------------------------------------------ CP-COR-60 / 61

    @Test
    void recuperacion_incluyeElEnlaceConElTokenEnElFragmento() { // CP-COR-60
        MensajeCorreo correo = plantillas.recuperacion(DESTINATARIO, TOKEN, VALIDEZ);

        assertThat(correo.cuerpo()).contains("https://esibuy.es/restablecer-contrasena#token=" + TOKEN);
    }

    @Test
    void recuperacion_elTokenSoloApareceUnaVezYNuncaFueraDelFragmento() { // CP-COR-61
        MensajeCorreo correo = plantillas.recuperacion(DESTINATARIO, TOKEN, VALIDEZ);

        // Un token en la query (?token=) viajaria al servidor y a los logs de proxys; en el asunto, a las notificaciones
        assertThat(correo.cuerpo().split(java.util.regex.Pattern.quote(TOKEN), -1)).hasSize(2);
        assertThat(correo.cuerpo()).doesNotContain("?token=", "&token=", "?");
        assertThat(correo.asunto()).doesNotContain(TOKEN);
    }

    // ------------------------------------------------------------------ CP-COR-62 / 63

    @Test
    void recuperacion_vaDirigidaAlDestinatarioConAsuntoYTextoPlano() { // CP-COR-62
        MensajeCorreo correo = plantillas.recuperacion(DESTINATARIO, TOKEN, VALIDEZ);

        assertThat(correo.destinatario()).isEqualTo(DESTINATARIO);
        assertThat(correo.asunto()).containsIgnoringCase("contraseña").doesNotContain("\n", "\r");
        // Texto plano: sin etiquetas HTML que alteren el enlace o permitan ocultar texto
        assertThat(correo.cuerpo()).doesNotContainPattern("<[a-zA-Z/][^>]*>");
    }

    @Test
    void recuperacion_indicaLaValidezEnMinutosYQueSeIgnoreSiNoLoPidio() { // CP-COR-63
        MensajeCorreo correo = plantillas.recuperacion(DESTINATARIO, TOKEN, VALIDEZ);

        assertThat(correo.cuerpo()).containsIgnoringCase("15 minutos");
        assertThat(correo.cuerpo()).containsIgnoringCase("ignora este mensaje");
    }

    @Test
    void recuperacion_validezDeUnMinuto_laEscribeEnSingular() { // CP-COR-63
        MensajeCorreo correo = plantillas.recuperacion(DESTINATARIO, TOKEN, Duration.ofMinutes(1));

        assertThat(correo.cuerpo()).contains("1 minuto").doesNotContain("1 minutos");
    }

    // ------------------------------------------------------------------ CP-COR-64 / 65

    @ParameterizedTest(name = "token no valido: [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {
            "corto",
            "con espacios en medio de un token largo de prueba 123456",
            "token-con-salto\r\nBcc: espia@malo.es-0123456789abcdef",
            "token/con/barras/0123456789abcdefghijklmnopqrstuvwxyz",
            "token#con#almohadillas0123456789abcdefghijklmnopqrstuvwxyz",
            "token?query=1-0123456789abcdefghijklmnopqrstuvwxyz",
            "token<script>0123456789abcdefghijklmnopqrstuvwxyz" })
    void recuperacion_tokenQueNoEsBase64Url_seRechaza(String token) { // CP-COR-64
        // Solo letras, digitos, guion y guion bajo: asi no se puede colar texto ni alterar la URL desde el token
        assertThrows(IllegalArgumentException.class, () -> plantillas.recuperacion(DESTINATARIO, token, VALIDEZ));
    }

    @Test
    void recuperacion_validezNoPositiva_seRechaza() { // CP-COR-65
        assertThrows(IllegalArgumentException.class,
                () -> plantillas.recuperacion(DESTINATARIO, TOKEN, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> plantillas.recuperacion(DESTINATARIO, TOKEN, Duration.ofMinutes(-5)));
        assertThrows(IllegalArgumentException.class, () -> plantillas.recuperacion(DESTINATARIO, TOKEN, null));
    }

    // ------------------------------------------------------------------ CP-COR-66

    @Test
    void recuperacion_usaLaUrlBaseDeLaConfiguracion() { // CP-COR-66
        PlantillasCorreo otraUrl = new PlantillasCorreo(
                PropiedadesCorreoPrueba.remoto().urlBaseFrontend("https://tienda.ejemplo.es/app/").construir());

        MensajeCorreo correo = otraUrl.recuperacion(DESTINATARIO, TOKEN, VALIDEZ);

        assertThat(correo.cuerpo()).contains("https://tienda.ejemplo.es/app/restablecer-contrasena#token=" + TOKEN);
    }

    // ------------------------------------------------------------------ CP-COR-67 / 68 / 69

    @Test
    void cuentaPendiente_avisaDeQueEstaPendienteDeActivacionYNoPuedeCambiarLaContrasena() { // CP-COR-67
        MensajeCorreo correo = plantillas.cuentaPendiente(DESTINATARIO);

        assertThat(correo.destinatario()).isEqualTo(DESTINATARIO);
        assertThat(correo.cuerpo()).containsIgnoringCase("pendiente de activación");
        assertThat(correo.cuerpo()).containsIgnoringCase("no puedes cambiar");
        assertThat(correo.asunto()).doesNotContain("\n", "\r");
    }

    @Test
    void cuentaPendiente_noLlevaEnlaceNiToken() { // CP-COR-68
        MensajeCorreo correo = plantillas.cuentaPendiente(DESTINATARIO);

        assertThat(correo.cuerpo()).doesNotContainIgnoringCase("http", "token", "restablecer-contrasena");
        assertThat(correo.cuerpo()).doesNotContainPattern("<[a-zA-Z/][^>]*>");
    }

    @Test
    void cuentaPendiente_destinatarioNoValido_seRechaza() { // CP-COR-69
        assertThrows(IllegalArgumentException.class, () -> plantillas.cuentaPendiente("ana@ejemplo.es\r\nBcc: x@y.es"));
        assertThrows(IllegalArgumentException.class, () -> plantillas.cuentaPendiente(null));
    }
}
