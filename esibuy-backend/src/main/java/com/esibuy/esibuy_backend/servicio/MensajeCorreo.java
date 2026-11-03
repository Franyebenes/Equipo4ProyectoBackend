package com.esibuy.esibuy_backend.servicio;

import java.util.regex.Pattern;

/**
 * Correo de texto plano con un unico destinatario. Es la ultima barrera antes del servidor SMTP: rechaza los saltos de
 * linea en destinatario y asunto (inyeccion de cabeceras, que permitiria anadir destinatarios ocultos) y cualquier
 * destinatario que no sea una unica direccion simple. Tests: MensajeCorreoTest (CP-COR-01 a CP-COR-06).
 *
 * @param destinatario direccion unica de destino
 * @param asunto       asunto en una sola linea
 * @param cuerpo       texto plano
 */
public record MensajeCorreo(String destinatario, String asunto, String cuerpo) {

    /** Una sola direccion: sin espacios (ni saltos de linea), comas, punto y coma, comillas ni angulos. */
    static final Pattern DIRECCION_SIMPLE = Pattern.compile("^[^\\s@,;<>\"\\\\]+@[^\\s@,;<>\"\\\\]+$");

    private static final Pattern SALTO_DE_LINEA = Pattern.compile("[\\r\\n]");

    public MensajeCorreo {
        if (destinatario == null || !DIRECCION_SIMPLE.matcher(destinatario).matches()) {
            throw new IllegalArgumentException("El destinatario debe ser una unica direccion de correo valida");
        }
        if (asunto == null || asunto.isBlank() || SALTO_DE_LINEA.matcher(asunto).find()) {
            throw new IllegalArgumentException("El asunto es obligatorio y no puede tener saltos de linea");
        }
        if (cuerpo == null || cuerpo.isBlank()) {
            throw new IllegalArgumentException("El cuerpo del correo es obligatorio");
        }
    }

    /** Nunca incluye el cuerpo: lleva el enlace con el token de recuperacion y no debe acabar en un log. */
    @Override
    public String toString() {
        return "MensajeCorreo[asunto=" + asunto + "]";
    }
}
