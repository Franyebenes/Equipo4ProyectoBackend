package com.esibuy.esibuy_backend.configuracion;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.esibuy.esibuy_backend.servicio.CifradorSecretos;
import com.esibuy.esibuy_backend.servicio.GeneradorCodigosRecuperacion;
import com.esibuy.esibuy_backend.servicio.GeneradorTotp;
import com.esibuy.esibuy_backend.servicio.PropiedadesDosFactores;

/**
 * Enlace de esibuy.dosfactores.* con los ficheros application.properties y application-prod.properties reales, y beans
 * de la fase 0 (HdU 2FA). Casos: CP-2FA-79 a CP-2FA-88.
 *
 * Lo que fijan: en desarrollo la aplicacion arranca con una clave de cifrado de pruebas y los limites acordados; en
 * produccion NO hay clave por defecto (ESIBUY_2FA_CLAVE), asi que olvidarla impide arrancar en lugar de cifrar con una
 * clave conocida; y los tres componentes (cifrador, generador TOTP y codigos de recuperacion) se crean a partir de esa
 * configuracion, con el reloj y el pepper de la aplicacion.
 *
 * Terminada: ya no lleva la etiqueta pendiente-2fa y entra en la regresion.
 */
class ConfiguracionDosFactoresTest {

    private static final Instant AHORA = Instant.parse("2026-10-17T10:00:00Z");

    @Configuration
    @EnableConfigurationProperties(PropiedadesDosFactores.class)
    static class SoloPropiedades {
    }

    @Configuration
    static class RelojDePrueba {
        @Bean
        Clock reloj() {
            return Clock.fixed(AHORA, ZoneId.of("Europe/Madrid"));
        }
    }

    private static String claveBase64(int semilla) {
        byte[] clave = new byte[32];
        for (int i = 0; i < clave.length; i++) {
            clave[i] = (byte) (semilla + i);
        }
        return Base64.getEncoder().encodeToString(clave);
    }

    /** Carga application.properties (y application-prod.properties si se activa el perfil) como lo hace la app. */
    private ApplicationContextRunner arrancarSoloPropiedades(String... perfiles) {
        return new ApplicationContextRunner()
                .withUserConfiguration(SoloPropiedades.class)
                .withInitializer(contexto -> contexto.getEnvironment().setActiveProfiles(perfiles))
                .withInitializer(new ConfigDataApplicationContextInitializer());
    }

    private ApplicationContextRunner arrancarConBeans() {
        return new ApplicationContextRunner()
                .withUserConfiguration(RelojDePrueba.class, ConfiguracionDosFactores.class)
                .withInitializer(new ConfigDataApplicationContextInitializer());
    }

    // ---------------------------------------------------------------- propiedades

    @Test
    void arrancar_sinPerfil_usaLosValoresAcordadosYUnaClaveDeDesarrollo() { // CP-2FA-79
        arrancarSoloPropiedades().run(contexto -> {
            assertThat(contexto).hasNotFailed();
            PropiedadesDosFactores propiedades = contexto.getBean(PropiedadesDosFactores.class);
            assertThat(propiedades.emisor()).isEqualTo("ESIBuy");
            assertThat(propiedades.versionActiva()).isEqualTo(1);
            assertThat(propiedades.clavesEnBytes().get(1)).hasSize(32);
            assertThat(propiedades.caducidadActivacion()).isEqualTo(Duration.ofMinutes(10));
            assertThat(propiedades.caducidadDesafio()).isEqualTo(Duration.ofMinutes(5));
            assertThat(propiedades.maxIntentosDesafio()).isEqualTo(5);
            assertThat(propiedades.maxFallosCuenta()).isEqualTo(10);
            assertThat(propiedades.ventanaCuenta()).isEqualTo(Duration.ofMinutes(15));
            assertThat(propiedades.maxPorIp()).isEqualTo(30);
            assertThat(propiedades.ventanaIp()).isEqualTo(Duration.ofHours(1));
            assertThat(propiedades.codigosRecuperacion()).isEqualTo(10);
        });
    }

    @Test
    void arrancar_conTodosLosValores_losEnlazaCorrectamente() { // CP-2FA-80
        arrancarSoloPropiedades().withPropertyValues(
                "esibuy.dosfactores.emisor=MiTienda",
                "esibuy.dosfactores.claves.1=" + claveBase64(1),
                "esibuy.dosfactores.claves.2=" + claveBase64(2),
                "esibuy.dosfactores.version-activa=2",
                "esibuy.dosfactores.caducidad-activacion=PT20M",
                "esibuy.dosfactores.caducidad-desafio=PT3M",
                "esibuy.dosfactores.max-intentos-desafio=4",
                "esibuy.dosfactores.max-fallos-cuenta=8",
                "esibuy.dosfactores.ventana-cuenta=PT30M",
                "esibuy.dosfactores.max-por-ip=50",
                "esibuy.dosfactores.ventana-ip=PT2H",
                "esibuy.dosfactores.codigos-recuperacion=8").run(contexto -> {
                    assertThat(contexto).hasNotFailed();
                    PropiedadesDosFactores propiedades = contexto.getBean(PropiedadesDosFactores.class);
                    assertThat(propiedades.emisor()).isEqualTo("MiTienda");
                    assertThat(propiedades.claves()).containsOnlyKeys(1, 2);
                    assertThat(propiedades.versionActiva()).isEqualTo(2);
                    assertThat(propiedades.caducidadActivacion()).isEqualTo(Duration.ofMinutes(20));
                    assertThat(propiedades.caducidadDesafio()).isEqualTo(Duration.ofMinutes(3));
                    assertThat(propiedades.maxIntentosDesafio()).isEqualTo(4);
                    assertThat(propiedades.maxFallosCuenta()).isEqualTo(8);
                    assertThat(propiedades.ventanaCuenta()).isEqualTo(Duration.ofMinutes(30));
                    assertThat(propiedades.maxPorIp()).isEqualTo(50);
                    assertThat(propiedades.ventanaIp()).isEqualTo(Duration.ofHours(2));
                    assertThat(propiedades.codigosRecuperacion()).isEqualTo(8);
                });
    }

    @Test
    void arrancar_conPerfilProdSinClave_fallaEnLugarDeUsarUnaClaveConocida() { // CP-2FA-81
        // No hay ESIBUY_2FA_CLAVE ni valor por defecto: el arranque debe fallar
        arrancarSoloPropiedades("prod").run(contexto -> assertThat(contexto).hasFailed());
    }

    @Test
    void arrancar_conPerfilProdYClave_losEnlazaCorrectamente() { // CP-2FA-82
        arrancarSoloPropiedades("prod").withPropertyValues("esibuy.dosfactores.claves.1=" + claveBase64(9))
                .run(contexto -> {
                    assertThat(contexto).hasNotFailed();
                    PropiedadesDosFactores propiedades = contexto.getBean(PropiedadesDosFactores.class);
                    assertThat(propiedades.clavesEnBytes().get(1)).hasSize(32);
                    assertThat(propiedades.versionActiva()).isEqualTo(1);
                });
    }

    @Test
    void produccion_laClaveSeLeeDeLaVariableDeEntornoYNoLlevaValorLiteral() throws IOException { // CP-2FA-83
        List<String> lineas;
        try (InputStream entrada = getClass().getClassLoader().getResourceAsStream("application-prod.properties")) {
            assertThat(entrada).isNotNull();
            lineas = Arrays.asList(new String(entrada.readAllBytes(), StandardCharsets.UTF_8).split("\\R"));
        }

        List<String> lineasDeClave = lineas.stream().filter(l -> l.startsWith("esibuy.dosfactores.claves.")).toList();

        assertThat(lineasDeClave).isNotEmpty();
        assertThat(lineasDeClave).allMatch(l -> l.contains("${ESIBUY_2FA_CLAVE"), "toda clave sale de ESIBUY_2FA_CLAVE");
        // y sin valor por defecto tras los dos puntos
        assertThat(lineasDeClave).noneMatch(l -> l.contains("ESIBUY_2FA_CLAVE:"));
    }

    @Test
    void arrancar_conUnaClaveDeTamanoIncorrecto_fallaElArranque() { // CP-2FA-84
        arrancarSoloPropiedades().withPropertyValues("esibuy.dosfactores.claves.1=AAAA")
                .run(contexto -> assertThat(contexto).hasFailed());
    }

    @Test
    void arrancar_conUnLimiteAbsurdo_fallaElArranque() { // CP-2FA-85
        arrancarSoloPropiedades().withPropertyValues("esibuy.dosfactores.max-intentos-desafio=0")
                .run(contexto -> assertThat(contexto).hasFailed());
        arrancarSoloPropiedades().withPropertyValues("esibuy.dosfactores.ventana-ip=PT0S")
                .run(contexto -> assertThat(contexto).hasFailed());
        arrancarSoloPropiedades().withPropertyValues("esibuy.dosfactores.emisor=ESI:Buy")
                .run(contexto -> assertThat(contexto).hasFailed());
    }

    // ---------------------------------------------------------------- beans

    @Test
    void arrancar_creaElCifradorConLaClaveActiva() { // CP-2FA-86
        arrancarConBeans().withPropertyValues("esibuy.seguridad.pepper=pepper-de-pruebas").run(contexto -> {
            assertThat(contexto).hasNotFailed();
            CifradorSecretos cifrador = contexto.getBean(CifradorSecretos.class);
            byte[] secreto = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

            String cifrado = cifrador.cifrar(secreto, "usuario-1");

            assertThat(cifrado).startsWith("1:");
            assertThat(cifrador.descifrar(cifrado, "usuario-1")).isEqualTo(secreto);
        });
    }

    @Test
    void arrancar_creaElGeneradorTotpConElRelojDeLaAplicacion() { // CP-2FA-87
        arrancarConBeans().withPropertyValues("esibuy.seguridad.pepper=pepper-de-pruebas").run(contexto -> {
            assertThat(contexto).hasNotFailed();
            GeneradorTotp generador = contexto.getBean(GeneradorTotp.class);

            assertThat(generador.pasoActual()).isEqualTo(AHORA.getEpochSecond() / 30);
        });
    }

    @Test
    void arrancar_creaElGeneradorDeCodigosDeRecuperacionConElPepperDeLaAplicacion() { // CP-2FA-88
        arrancarConBeans().withPropertyValues("esibuy.seguridad.pepper=pepper-de-pruebas").run(contexto -> {
            assertThat(contexto).hasNotFailed();
            GeneradorCodigosRecuperacion generador = contexto.getBean(GeneradorCodigosRecuperacion.class);

            assertThat(generador.hash("ABCDEFGH23456723"))
                    .isEqualTo(new GeneradorCodigosRecuperacion("pepper-de-pruebas").hash("ABCDEFGH23456723"));
        });
    }

    @Test
    void arrancar_sinPepper_fallaElArranque() { // CP-2FA-88
        // Igual que con las contrasenas: sin pepper no se puede proteger el hash de los codigos de recuperacion
        arrancarConBeans().withPropertyValues("esibuy.seguridad.pepper=")
                .run(contexto -> assertThat(contexto).hasFailed());
    }
}
