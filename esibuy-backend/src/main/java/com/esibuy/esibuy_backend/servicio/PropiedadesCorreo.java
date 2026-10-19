package com.esibuy.esibuy_backend.servicio;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuracion del correo saliente (prefijo esibuy.correo). Se valida al construirla: una configuracion insegura
 * impide arrancar la aplicacion en lugar de enviar correos sin proteccion. Tests: PropiedadesCorreoTest
 * (CP-COR-10 a CP-COR-21) y ConfiguracionCorreoPropiedadesTest (CP-COR-25 a CP-COR-30).
 *
 * <p>Reglas: sin cifrado solo hacia el propio equipo (servidor de pruebas Mailpit); un servidor remoto exige TLS y
 * credenciales; la URL base del frontend, con la que se construye el enlace de recuperacion, es HTTPS (HTTP solo hacia
 * el propio equipo) y no lleva usuario, query ni fragmento.
 *
 * @param host            servidor SMTP
 * @param puerto          puerto SMTP
 * @param tls             cifrado de la conexion (por defecto STARTTLS)
 * @param usuario         usuario SMTP (vacio si no hay autenticacion)
 * @param contrasena      contrasena SMTP (nunca sale en toString ni en logs)
 * @param remitente       direccion que figura como remitente
 * @param urlBaseFrontend URL base del frontend, sin barra final
 * @param tiempoConexion  tiempo maximo para conectar
 * @param tiempoLectura   tiempo maximo de espera de respuesta del servidor
 */
@ConfigurationProperties(prefix = "esibuy.correo")
public record PropiedadesCorreo(
        String host,
        int puerto,
        @DefaultValue("STARTTLS") ModoTls tls,
        String usuario,
        String contrasena,
        String remitente,
        String urlBaseFrontend,
        @DefaultValue("PT5S") Duration tiempoConexion,
        @DefaultValue("PT10S") Duration tiempoLectura) {

    private static final Pattern IPV4_LOOPBACK =
            Pattern.compile("^127\\.(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){2}(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)$");
    private static final Pattern BARRAS_FINALES = Pattern.compile("/+$");

    public PropiedadesCorreo {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("esibuy.correo.host es obligatorio");
        }
        if (puerto < 1 || puerto > 65535) {
            throw new IllegalArgumentException("esibuy.correo.puerto debe estar entre 1 y 65535");
        }
        if (tls == null) {
            throw new IllegalArgumentException("esibuy.correo.tls es obligatorio");
        }
        boolean local = esPropioEquipo(host);
        if (tls == ModoTls.NINGUNO && !local) {
            throw new IllegalArgumentException("Sin cifrado solo se admite el SMTP del propio equipo (localhost)");
        }
        validarCredenciales(usuario, contrasena, local);
        if (remitente == null || !MensajeCorreo.DIRECCION_SIMPLE.matcher(remitente).matches()) {
            throw new IllegalArgumentException("esibuy.correo.remitente debe ser una unica direccion de correo valida");
        }
        urlBaseFrontend = validarUrlBase(urlBaseFrontend);
        comprobarPositivo(tiempoConexion, "esibuy.correo.tiempo-conexion");
        comprobarPositivo(tiempoLectura, "esibuy.correo.tiempo-lectura");
    }

    /** Hay autenticacion cuando se ha configurado usuario (validado: entonces tambien hay contrasena). */
    public boolean usaAutenticacion() {
        return usuario != null && !usuario.isBlank();
    }

    /** Nunca incluye la contrasena. */
    @Override
    public String toString() {
        return "PropiedadesCorreo[host=" + host + ", puerto=" + puerto + ", tls=" + tls + ", usuario=" + usuario
                + ", contrasena=***, remitente=" + remitente + ", urlBaseFrontend=" + urlBaseFrontend + "]";
    }

    private static void validarCredenciales(String usuario, String contrasena, boolean local) {
        boolean hayUsuario = usuario != null && !usuario.isBlank();
        boolean hayContrasena = contrasena != null && !contrasena.isBlank();
        if (hayUsuario != hayContrasena) {
            throw new IllegalArgumentException("El usuario y la contrasena SMTP van juntos: faltan unas credenciales");
        }
        if (!hayUsuario && !local) {
            throw new IllegalArgumentException("Un servidor SMTP remoto exige credenciales");
        }
    }

    private static String validarUrlBase(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("esibuy.correo.url-base-frontend es obligatoria");
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("esibuy.correo.url-base-frontend no es una URL valida", e);
        }
        String esquema = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String servidor = uri.getHost();
        boolean cifrada = "https".equals(esquema);
        boolean httpLocal = "http".equals(esquema) && servidor != null && esPropioEquipo(servidor);
        if (!(cifrada || httpLocal) || servidor == null) {
            throw new IllegalArgumentException("La URL base debe ser HTTPS (HTTP solo hacia el propio equipo)");
        }
        if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("La URL base no puede llevar usuario, query ni fragmento");
        }
        return BARRAS_FINALES.matcher(url).replaceFirst("");
    }

    /** Decision por texto, sin resolver DNS: "localhost", "::1" o 127.x.x.x. Nada que solo lo parezca. */
    private static boolean esPropioEquipo(String host) {
        String limpio = host.strip().toLowerCase(Locale.ROOT);
        if (limpio.startsWith("[") && limpio.endsWith("]")) {
            limpio = limpio.substring(1, limpio.length() - 1);
        }
        return "localhost".equals(limpio) || "::1".equals(limpio) || IPV4_LOOPBACK.matcher(limpio).matches();
    }

    private static void comprobarPositivo(Duration tiempo, String nombre) {
        if (tiempo == null || tiempo.isZero() || tiempo.isNegative()) {
            throw new IllegalArgumentException(nombre + " debe ser una duracion positiva");
        }
    }
}
