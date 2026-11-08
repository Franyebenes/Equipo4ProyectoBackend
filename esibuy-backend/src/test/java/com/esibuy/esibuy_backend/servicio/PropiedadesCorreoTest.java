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
 * Reglas de seguridad de la configuracion del correo (HdU recuperacion de contrasena, fase 0).
 * Casos: CP-COR-10 a CP-COR-21.
 *
 * Regla central: una configuracion insegura no debe poder arrancar. Sin cifrado solo se admite hacia el propio equipo
 * (servidor de pruebas Mailpit); cualquier servidor remoto exige TLS y credenciales. La URL con la que se construye el
 * enlace de recuperacion tambien se valida, porque es lo que el usuario acaba pulsando.
 *
 * Terminada: ya no lleva la etiqueta pendiente-recuperacion y entra en la regresion.
 */
class PropiedadesCorreoTest {

    private static PropiedadesCorreoPrueba remoto() {
        return PropiedadesCorreoPrueba.remoto();
    }

    // ------------------------------------------------------------------ CP-COR-10

    @Test
    void crear_servidorRemotoConStarttlsYCredenciales_esValida() { // CP-COR-10
        PropiedadesCorreo propiedades = remoto().construir();

        assertThat(propiedades.host()).isEqualTo("smtp.ejemplo.es");
        assertThat(propiedades.tls()).isEqualTo(ModoTls.STARTTLS);
    }

    @Test
    void crear_servidorRemotoConTlsImplicitoYCredenciales_esValida() { // CP-COR-10
        PropiedadesCorreo propiedades = remoto().puerto(465).tls(ModoTls.TLS_IMPLICITO).construir();

        assertThat(propiedades.tls()).isEqualTo(ModoTls.TLS_IMPLICITO);
    }

    // ------------------------------------------------------------------ CP-COR-11 / 12 / 13

    @Test
    void crear_servidorRemotoSinCifrado_seRechaza() { // CP-COR-11
        PropiedadesCorreoPrueba sinCifrado = remoto().tls(ModoTls.NINGUNO);

        assertThrows(IllegalArgumentException.class, sinCifrado::construir);
    }

    @ParameterizedTest(name = "sin cifrado hacia {0} esta permitido")
    @ValueSource(strings = { "localhost", "LOCALHOST", "127.0.0.1", "127.0.0.2", "::1", "[::1]" })
    void crear_sinCifradoHaciaElPropioEquipo_esValida(String host) { // CP-COR-12
        PropiedadesCorreo propiedades = PropiedadesCorreoPrueba.local(1025).host(host).construir();

        assertThat(propiedades.tls()).isEqualTo(ModoTls.NINGUNO);
    }

    @ParameterizedTest(name = "{0} no es el propio equipo")
    @ValueSource(strings = {
            "localhost.evil.com",
            "127.0.0.1.nip.io",
            "mi-localhost",
            "10.0.0.5",
            "192.168.1.20",
            "0.0.0.0" })
    void crear_sinCifradoHaciaUnHostQuePareceLocalPeroNoLoEs_seRechaza(String host) { // CP-COR-13
        PropiedadesCorreoPrueba trampa = remoto().host(host).tls(ModoTls.NINGUNO);

        assertThrows(IllegalArgumentException.class, trampa::construir);
    }

    // ------------------------------------------------------------------ CP-COR-14 / 15

    @Test
    void crear_servidorRemotoSinCredenciales_seRechaza() { // CP-COR-14
        PropiedadesCorreoPrueba sinCredenciales = remoto().usuario(null).contrasena(null);

        assertThrows(IllegalArgumentException.class, sinCredenciales::construir);
    }

    @Test
    void crear_usuarioSinContrasenaOContrasenaSinUsuario_seRechaza() { // CP-COR-15
        assertThrows(IllegalArgumentException.class, remoto().contrasena(null)::construir);
        assertThrows(IllegalArgumentException.class, remoto().contrasena("  ")::construir);
        assertThrows(IllegalArgumentException.class, remoto().usuario(null)::construir);
        assertThrows(IllegalArgumentException.class, remoto().usuario("  ")::construir);
    }

    // ------------------------------------------------------------------ CP-COR-16 / 17

    @ParameterizedTest(name = "host vacio o nulo: [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = { "  " })
    void crear_hostVacio_seRechaza(String host) { // CP-COR-16
        assertThrows(IllegalArgumentException.class, remoto().host(host)::construir);
    }

    @ParameterizedTest(name = "puerto fuera de rango: {0}")
    @ValueSource(ints = { -1, 0, 65536, 100000 })
    void crear_puertoFueraDeRango_seRechaza(int puerto) { // CP-COR-16
        assertThrows(IllegalArgumentException.class, remoto().puerto(puerto)::construir);
    }

    @ParameterizedTest(name = "remitente no valido: {0}")
    @NullAndEmptySource
    @ValueSource(strings = {
            "sin-arroba",
            "no-reply@esibuy.es\r\nBcc: espia@malo.es",
            "a@b.es, c@d.es",
            "Esibuy <no-reply@esibuy.es>" })
    void crear_remitenteNoValido_seRechaza(String remitente) { // CP-COR-17
        assertThrows(IllegalArgumentException.class, remoto().remitente(remitente)::construir);
    }

    // ------------------------------------------------------------------ CP-COR-18 / 21

    @ParameterizedTest(name = "url base valida: {0}")
    @ValueSource(strings = { "https://esibuy.es", "https://app.esibuy.es:8443", "https://esibuy.es/app" })
    void crear_urlBaseHttps_esValida(String url) { // CP-COR-18
        assertThat(remoto().urlBaseFrontend(url).construir().urlBaseFrontend()).isEqualTo(url);
    }

    @ParameterizedTest(name = "http solo hacia el propio equipo: {0}")
    @ValueSource(strings = { "http://localhost:43127", "http://127.0.0.1:4200" })
    void crear_urlBaseHttpHaciaElPropioEquipo_esValida(String url) { // CP-COR-18
        assertThat(PropiedadesCorreoPrueba.local(1025).urlBaseFrontend(url).construir().urlBaseFrontend())
                .isEqualTo(url);
    }

    @ParameterizedTest(name = "url base no valida: {0}")
    @NullAndEmptySource
    @ValueSource(strings = {
            "http://esibuy.es",
            "http://localhost.evil.com",
            "ftp://esibuy.es",
            "javascript:alert(1)",
            "esibuy.es",
            "//esibuy.es",
            "https://usuario:clave@esibuy.es",
            "https://esibuy.es?redirigir=https://malo.es",
            "https://esibuy.es#token=1",
            "https://esibuy.es/a b",
            "https://esibuy.es/\r\nX: y" })
    void crear_urlBaseNoValida_seRechaza(String url) { // CP-COR-18
        assertThrows(IllegalArgumentException.class, remoto().urlBaseFrontend(url)::construir);
    }

    @Test
    void crear_urlBaseConBarraFinal_seNormalizaSinLaBarra() { // CP-COR-21
        // Given / When: el enlace se construye anadiendo una ruta, asi que la barra final sobraria
        PropiedadesCorreo propiedades = remoto().urlBaseFrontend("https://esibuy.es/").construir();

        // Then
        assertThat(propiedades.urlBaseFrontend()).isEqualTo("https://esibuy.es");
    }

    // ------------------------------------------------------------------ CP-COR-19 / 20

    @Test
    void toString_nuncaIncluyeLaContrasenaSmtp() { // CP-COR-19
        String texto = remoto().construir().toString();

        assertThat(texto).doesNotContain(PropiedadesCorreoPrueba.CONTRASENA_SMTP);
    }

    @Test
    void crear_tiemposDeEsperaNoPositivos_seRechazan() { // CP-COR-20
        // Sin tiempos de espera un servidor SMTP que no responde dejaria colgado el hilo de envio para siempre
        assertThrows(IllegalArgumentException.class, remoto().tiempoConexion(Duration.ZERO)::construir);
        assertThrows(IllegalArgumentException.class, remoto().tiempoConexion(Duration.ofSeconds(-1))::construir);
        assertThrows(IllegalArgumentException.class, remoto().tiempoLectura(Duration.ZERO)::construir);
        assertThrows(IllegalArgumentException.class, remoto().tiempoLectura(null)::construir);
    }
}
