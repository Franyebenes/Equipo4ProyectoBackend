package com.esibuy.esibuy_backend.servicio;

import java.nio.charset.StandardCharsets;

import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

import com.esibuy.esibuy_backend.excepcion.FalloEnvioCorreoException;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

/**
 * Envio real por SMTP, con el cifrado y los tiempos de espera que fija ConfiguracionCorreo. Un unico destinatario,
 * sin copias, texto plano en UTF-8. Cualquier fallo se convierte en {@link FalloEnvioCorreoException} con un mensaje
 * fijo (la causa original se conserva solo para el diagnostico). Tests: EnviadorCorreoSmtpTest (CP-COR-50 a CP-COR-57).
 */
public class EnviadorCorreoSmtp implements EnviadorCorreo {

    private final JavaMailSender emisor;
    private final String remitente;

    public EnviadorCorreoSmtp(JavaMailSender emisor, PropiedadesCorreo propiedades) {
        this.emisor = emisor;
        this.remitente = propiedades.remitente();
    }

    @Override
    public void enviar(MensajeCorreo mensaje) {
        try {
            MimeMessage correo = emisor.createMimeMessage();
            MimeMessageHelper ayudante = new MimeMessageHelper(correo, false, StandardCharsets.UTF_8.name());
            ayudante.setFrom(remitente);
            ayudante.setTo(mensaje.destinatario());
            ayudante.setSubject(mensaje.asunto());
            ayudante.setText(mensaje.cuerpo(), false);
            emisor.send(correo);
        } catch (MailException | MessagingException e) {
            throw new FalloEnvioCorreoException("No se pudo enviar el correo", e);
        }
    }
}
