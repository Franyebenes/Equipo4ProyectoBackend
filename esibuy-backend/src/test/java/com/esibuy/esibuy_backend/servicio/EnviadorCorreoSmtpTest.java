package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;

import javax.net.ssl.SSLException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.esibuy.esibuy_backend.configuracion.ConfiguracionCorreo;
import com.esibuy.esibuy_backend.excepcion.FalloEnvioCorreoException;
import com.esibuy.esibuy_backend.util.PropiedadesCorreoPrueba;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;

import jakarta.mail.Address;
import jakarta.mail.internet.MimeMessage;

/**
 * Envio real por SMTP contra un servidor en memoria (GreenMail), sin salir de la maquina
 * (HdU recuperacion de contrasena, fase 0). Casos: CP-COR-50 a CP-COR-57.
 *
 * El EnviadorCorreoSmtp se construye con el JavaMailSender que fabrica ConfiguracionCorreo, asi que estas pruebas
 * cubren tambien que la configuracion funciona de verdad con un servidor SMTP.
 *
 * Terminada: ya no lleva la etiqueta pendiente-recuperacion y entra en la regresion.
 */
class EnviadorCorreoSmtpTest {

    private static final String TOKEN_SECRETO = "TOKEN-SECRETO-9876543210";
    private static final MensajeCorreo MENSAJE = new MensajeCorreo("ana@ejemplo.es",
            "Recuperación de tu contraseña",
            "Pulsa aquí: https://esibuy.es/restablecer-contrasena#token=" + TOKEN_SECRETO + "\nCaduca en 15 minutos.");

    private GreenMail servidor;

    @AfterEach
    void pararServidor() {
        if (servidor != null) {
            servidor.stop();
        }
    }

    private static int puertoLibre() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private void arrancarServidor(String protocolo, int puerto) {
        servidor = new GreenMail(new ServerSetup(puerto, "127.0.0.1", protocolo));
        servidor.start();
    }

    private static EnviadorCorreoSmtp enviadorPara(PropiedadesCorreoPrueba propiedades) {
        PropiedadesCorreo configuracion = propiedades.construir();
        return new EnviadorCorreoSmtp(new ConfiguracionCorreo().javaMailSender(configuracion), configuracion);
    }

    // ------------------------------------------------------------------ CP-COR-50 / 51 / 52

    @Test
    void enviar_servidorDisponible_entregaElCorreoConRemitenteAsuntoYCuerpo() throws Exception { // CP-COR-50
        // Given
        int puerto = puertoLibre();
        arrancarServidor(ServerSetup.PROTOCOL_SMTP, puerto);
        EnviadorCorreoSmtp enviador = enviadorPara(PropiedadesCorreoPrueba.local(puerto));

        // When
        enviador.enviar(MENSAJE);

        // Then
        assertThat(servidor.waitForIncomingEmail(5000, 1)).isTrue();
        MimeMessage recibido = servidor.getReceivedMessages()[0];
        assertThat(recibido.getFrom()).extracting(Address::toString).containsExactly("no-reply@esibuy.es");
        assertThat(recibido.getAllRecipients()).extracting(Address::toString).containsExactly("ana@ejemplo.es");
        assertThat(recibido.getSubject()).isEqualTo("Recuperación de tu contraseña");
        assertThat(recibido.getContentType()).startsWith("text/plain");
        assertThat((String) recibido.getContent()).isEqualToIgnoringNewLines(MENSAJE.cuerpo());
    }

    @Test
    void enviar_textoConTildesEnyesYComillas_llegaIntactoEnUtf8() throws Exception { // CP-COR-51
        // Given
        int puerto = puertoLibre();
        arrancarServidor(ServerSetup.PROTOCOL_SMTP, puerto);
        MensajeCorreo conAcentos = new MensajeCorreo("ana@ejemplo.es", "Contraseña: «recuperación» ñandú",
                "Café, año, pingüino, «comillas» y €.");

        // When
        enviadorPara(PropiedadesCorreoPrueba.local(puerto)).enviar(conAcentos);

        // Then
        assertThat(servidor.waitForIncomingEmail(5000, 1)).isTrue();
        MimeMessage recibido = servidor.getReceivedMessages()[0];
        assertThat(recibido.getSubject()).isEqualTo("Contraseña: «recuperación» ñandú");
        assertThat((String) recibido.getContent()).isEqualToIgnoringNewLines("Café, año, pingüino, «comillas» y €.");
    }

    @Test
    void enviar_siempreUnSoloDestinatarioSinCopias() throws Exception { // CP-COR-52
        // Given
        int puerto = puertoLibre();
        arrancarServidor(ServerSetup.PROTOCOL_SMTP, puerto);

        // When
        enviadorPara(PropiedadesCorreoPrueba.local(puerto)).enviar(MENSAJE);

        // Then: nadie mas recibe el enlace de recuperacion
        assertThat(servidor.waitForIncomingEmail(5000, 1)).isTrue();
        MimeMessage recibido = servidor.getReceivedMessages()[0];
        assertThat(recibido.getAllRecipients()).hasSize(1);
        assertThat(recibido.getRecipients(jakarta.mail.Message.RecipientType.CC)).isNull();
        assertThat(recibido.getRecipients(jakarta.mail.Message.RecipientType.BCC)).isNull();
    }

    // ------------------------------------------------------------------ CP-COR-53 / 54 / 55 / 56

    @Test
    void enviar_servidorCaido_lanzaFalloDeEnvio() throws Exception { // CP-COR-53
        // Given: un puerto en el que no escucha nadie
        EnviadorCorreoSmtp enviador = enviadorPara(PropiedadesCorreoPrueba.local(puertoLibre()));

        // When / Then
        assertThrows(FalloEnvioCorreoException.class, () -> enviador.enviar(MENSAJE));
    }

    @Test
    void enviar_servidorQueNoResponde_terminaPorTiempoAgotadoSinColgarse() throws Exception { // CP-COR-54
        // Given: un servidor que acepta la conexion pero nunca saluda (tiempo de lectura de 1 s en la configuracion)
        try (ServerSocket mudo = new ServerSocket(0)) {
            EnviadorCorreoSmtp enviador = enviadorPara(PropiedadesCorreoPrueba.local(mudo.getLocalPort()));

            // When / Then
            assertTimeoutPreemptively(Duration.ofSeconds(8),
                    () -> assertThrows(FalloEnvioCorreoException.class, () -> enviador.enviar(MENSAJE)));
        }
    }

    @Test
    void enviar_tlsImplicitoConCertificadoNoConfiable_falla_y_noEntregaNada() throws Exception { // CP-COR-55
        // Given: un servidor con TLS cuyo certificado es autofirmado (no esta en el almacen de confianza)
        int puerto = puertoLibre();
        arrancarServidor(ServerSetup.PROTOCOL_SMTPS, puerto);
        EnviadorCorreoSmtp enviador = enviadorPara(
                PropiedadesCorreoPrueba.local(puerto).tls(ModoTls.TLS_IMPLICITO).tiempoConexion(Duration.ofSeconds(5))
                        .tiempoLectura(Duration.ofSeconds(5)));

        // When
        FalloEnvioCorreoException fallo = assertThrows(FalloEnvioCorreoException.class, () -> enviador.enviar(MENSAJE));

        // Then: el fallo viene del apreton de manos TLS (no de otra cosa) y el correo no llego
        assertThat(causas(fallo)).anyMatch(SSLException.class::isInstance);
        assertThat(servidor.getReceivedMessages()).isEmpty();
    }

    @Test
    void enviar_cuandoFalla_laExcepcionNoRevelaElCuerpoNiLasCredenciales() throws Exception { // CP-COR-56
        // Given: credenciales configuradas y un servidor caido
        EnviadorCorreoSmtp enviador = enviadorPara(PropiedadesCorreoPrueba.local(puertoLibre())
                .usuario("usuario-smtp").contrasena(PropiedadesCorreoPrueba.CONTRASENA_SMTP));

        // When
        FalloEnvioCorreoException fallo = assertThrows(FalloEnvioCorreoException.class, () -> enviador.enviar(MENSAJE));

        // Then: ni el mensaje ni ninguna causa contienen el token del enlace, el cuerpo o la contrasena SMTP
        for (Throwable causa : causas(fallo)) {
            String texto = String.valueOf(causa.getMessage());
            assertThat(texto).doesNotContain(TOKEN_SECRETO, MENSAJE.cuerpo(), PropiedadesCorreoPrueba.CONTRASENA_SMTP);
        }
    }

    @Test
    void enviar_despuesDeUnFallo_elSiguienteEnvioFuncionaSiElServidorVuelve() throws Exception { // CP-COR-57
        // Given: primero no hay servidor
        int puerto = puertoLibre();
        EnviadorCorreoSmtp enviador = enviadorPara(PropiedadesCorreoPrueba.local(puerto));
        assertThrows(FalloEnvioCorreoException.class, () -> enviador.enviar(MENSAJE));

        // When: el servidor arranca despues
        arrancarServidor(ServerSetup.PROTOCOL_SMTP, puerto);

        // Then: el mismo enviador entrega sin quedarse en un estado roto
        assertDoesNotThrow(() -> enviador.enviar(MENSAJE));
        assertThat(servidor.waitForIncomingEmail(5000, 1)).isTrue();
    }

    private static java.util.List<Throwable> causas(Throwable raiz) {
        java.util.List<Throwable> cadena = new java.util.ArrayList<>();
        for (Throwable actual = raiz; actual != null && !cadena.contains(actual); actual = actual.getCause()) {
            cadena.add(actual);
        }
        return cadena;
    }
}
