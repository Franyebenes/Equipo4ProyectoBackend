package com.esibuy.esibuy_backend.servicio;

import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Textos de los correos de la recuperacion de contrasena. Texto plano (sin HTML). El enlace se construye siempre con la
 * URL base de la configuracion y lleva el token en el fragmento (tras #), que el navegador nunca envia al servidor ni
 * en la cabecera Referer. El aviso de cuenta pendiente no lleva enlace ni token. Tests: PlantillasCorreoTest
 * (CP-COR-60 a CP-COR-69).
 */
public class PlantillasCorreo {

    /** Ruta de la pantalla de restablecimiento del frontend. */
    static final String RUTA_RESTABLECER = "/restablecer-contrasena";

    /** Solo base64url: asi el token no puede colar texto ni alterar la URL. */
    private static final Pattern FORMATO_TOKEN = Pattern.compile("^[A-Za-z0-9_-]{32,128}$");

    private final String urlBase;

    public PlantillasCorreo(PropiedadesCorreo propiedades) {
        this.urlBase = propiedades.urlBaseFrontend();
    }

    /** Correo con el enlace de recuperacion (cuenta activa). */
    public MensajeCorreo recuperacion(String destinatario, String token, Duration validez) {
        if (token == null || !FORMATO_TOKEN.matcher(token).matches()) {
            throw new IllegalArgumentException("El token de recuperacion no tiene el formato esperado");
        }
        if (validez == null || validez.isZero() || validez.isNegative()) {
            throw new IllegalArgumentException("La validez del enlace debe ser positiva");
        }
        String enlace = urlBase + RUTA_RESTABLECER + "#token=" + token;
        String cuerpo = """
                Hola,

                Hemos recibido una solicitud para restablecer la contraseña de tu cuenta de ESIBuy.

                Para elegir una contraseña nueva, abre este enlace:

                %s

                El enlace caduca en %s y solo puede usarse una vez.

                Si no has solicitado este cambio, ignora este mensaje: tu contraseña no se modificará.

                El equipo de ESIBuy
                """.formatted(enlace, minutos(validez));
        return new MensajeCorreo(destinatario, "Recuperación de tu contraseña de ESIBuy", cuerpo);
    }

    /** Correo que avisa de que la cuenta esta pendiente de activacion y aun no puede cambiar la contrasena. */
    public MensajeCorreo cuentaPendiente(String destinatario) {
        String cuerpo = """
                Hola,

                Hemos recibido una solicitud para recuperar la contraseña de esta cuenta de ESIBuy, pero tu cuenta \
                está pendiente de activación y por ahora no puedes cambiar la contraseña.

                Cuando tu cuenta sea activada podrás iniciar sesión y, si lo necesitas, volver a solicitar la \
                recuperación.

                Si no has hecho esta solicitud, ignora este mensaje.

                El equipo de ESIBuy
                """;
        return new MensajeCorreo(destinatario, "Tu cuenta de ESIBuy está pendiente de activación", cuerpo);
    }

    /** Aviso de que se ha activado la verificacion en dos pasos. Sin enlaces, secretos ni codigos. */
    public MensajeCorreo dosFactoresActivado(String destinatario) {
        String cuerpo = """
                Hola,

                Se ha activado la verificación en dos pasos en tu cuenta de ESIBuy. Desde ahora, al iniciar sesión \
                necesitarás también el código de tu aplicación autenticadora.

                Si no has sido tú, cambia tu contraseña cuanto antes y revisa la seguridad de tu cuenta.

                El equipo de ESIBuy
                """;
        return new MensajeCorreo(destinatario, "Verificación en dos pasos activada en tu cuenta de ESIBuy", cuerpo);
    }

    /** Aviso de que se han generado codigos de recuperacion nuevos: los anteriores ya no valen. */
    public MensajeCorreo codigosRecuperacionRegenerados(String destinatario) {
        String cuerpo = """
                Hola,

                Se han generado códigos de recuperación nuevos para tu cuenta de ESIBuy. Los anteriores han dejado de \
                funcionar.

                Si no has sido tú, cambia tu contraseña cuanto antes y revisa la seguridad de tu cuenta.

                El equipo de ESIBuy
                """;
        return new MensajeCorreo(destinatario, "Nuevos códigos de recuperación en tu cuenta de ESIBuy", cuerpo);
    }

    /** Minutos redondeados hacia arriba (nunca "0 minutos"), en singular o plural. */
    private static String minutos(Duration validez) {
        long minutos = Math.max(1, (validez.toSeconds() + 59) / 60);
        return minutos + (minutos == 1 ? " minuto" : " minutos");
    }
}
