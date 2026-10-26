package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.esibuy.esibuy_backend.excepcion.CodigoError;
import com.esibuy.esibuy_backend.excepcion.DatosRegistroInvalidosException;
import com.esibuy.esibuy_backend.excepcion.ServicioNoDisponibleException;
import com.esibuy.esibuy_backend.util.CapturadorLogs;
import com.esibuy.esibuy_backend.util.PropiedadesDosFactoresPrueba;

/**
 * Primer paso del 2FA en el registro: generar el secreto para un correo (HdU 2FA en el registro, fase A).
 * Casos: CP-2FA-397 a CP-2FA-410.
 *
 * ServicioRegistroDosFactores.preparar genera un secreto TOTP de 160 bits, lo cifra y devuelve lo necesario para el QR; el
 * controlador guarda el texto cifrado en la sesion (GestorRegistroDosFactores). Reglas que fijan:
 *  - No toca la base de datos (la cuenta todavia no existe) ni comprueba si el correo esta registrado: la respuesta es la
 *    misma exista o no, asi que no permite averiguar que correos hay (la unicidad se comprueba, con la respuesta generica de
 *    siempre, en el registro final).
 *  - El correo solo se valida en su formato (los mismos errores por campo que el registro, todos a la vez); no hay consulta
 *    DNS. Se normaliza igual que el registro (sin espacios en los extremos y en minusculas).
 *  - El secreto se cifra con el correo normalizado como dato asociado: un secreto pedido para un correo no se puede descifrar
 *    como si fuera de otro, que es lo que impide cambiar de correo entre los dos pasos.
 *  - La URI del QR lleva el emisor configurado y el correo como cuenta.
 *  - Se audita sin el secreto y con el correo enmascarado. Un fallo del cifrado es un 503 sin detalles.
 *
 * Terminada: ya no lleva la etiqueta pendiente-2fa y entra en la regresion.
 */
@ExtendWith(MockitoExtension.class)
class ServicioRegistroDosFactoresTest {

    private static final String EMAIL = "lucia.torres@ejemplo.es";
    private static final ContextoPeticion CONTEXTO = new ContextoPeticion("203.0.113.7", "JUnit");

    @Mock
    private AuditoriaSeguridad auditoria;

    private final CifradorSecretos cifrador = new CifradorSecretos(Map.of(1, PropiedadesDosFactoresPrueba.CLAVE), 1);
    private final GeneradorTotp totp = new GeneradorTotp(Clock.fixed(Instant.parse("2026-10-21T10:00:00Z"), ZoneId.of("UTC")));

    private ServicioRegistroDosFactores servicio;

    @BeforeEach
    void prepararServicio() {
        servicio = new ServicioRegistroDosFactores(cifrador, totp, auditoria, PropiedadesDosFactoresPrueba.acordadas());
    }

    @Test
    void preparar_devuelveUnSecretoDe160BitsYLaUriDelQrParaEseCorreo() { // CP-2FA-397
        PreparacionRegistroDosFactores preparacion = servicio.preparar(EMAIL, CONTEXTO);

        byte[] secreto = Base32.decodificar(preparacion.secreto());
        assertThat(secreto).hasSize(20);
        assertThat(preparacion.uri()).isEqualTo(UriOtpauth.construir("ESIBuy", EMAIL, secreto));
        assertThat(preparacion.email()).isEqualTo(EMAIL);
    }

    @Test
    void preparar_elSecretoCifradoSeDescifraConElCorreoComoDatoAsociado() { // CP-2FA-398
        PreparacionRegistroDosFactores preparacion = servicio.preparar(EMAIL, CONTEXTO);

        byte[] secreto = Base32.decodificar(preparacion.secreto());
        assertThat(preparacion.secretoCifrado()).matches("^1:[A-Za-z0-9_-]+$").doesNotContain(preparacion.secreto());
        assertThat(cifrador.descifrar(preparacion.secretoCifrado(), EMAIL)).isEqualTo(secreto);
        // Ligado a ese correo: con otro no se puede descifrar
        assertThrows(IllegalArgumentException.class, () -> cifrador.descifrar(preparacion.secretoCifrado(), "otro@ejemplo.es"));
    }

    @Test
    void preparar_normalizaElCorreoComoElRegistro() { // CP-2FA-399
        PreparacionRegistroDosFactores preparacion = servicio.preparar("  Lucia.TORRES@Ejemplo.ES ", CONTEXTO);

        assertThat(preparacion.email()).isEqualTo(EMAIL);
        assertThat(preparacion.uri()).contains(EMAIL.replace("@", "%40"));
        assertThat(cifrador.descifrar(preparacion.secretoCifrado(), EMAIL)).isNotEmpty();
    }

    @Test
    void preparar_dosVeces_daSecretosDistintos() { // CP-2FA-400
        String uno = servicio.preparar(EMAIL, CONTEXTO).secreto();
        String otro = servicio.preparar(EMAIL, CONTEXTO).secreto();

        assertThat(uno).isNotEqualTo(otro);
    }

    @ParameterizedTest(name = "correo \"{0}\"")
    @NullAndEmptySource
    @ValueSource(strings = { " ", "   " })
    void preparar_sinCorreo_esUnErrorDeValidacionObligatorio(String email) { // CP-2FA-401
        DatosRegistroInvalidosException error = assertThrows(DatosRegistroInvalidosException.class,
                () -> servicio.preparar(email, CONTEXTO));

        assertThat(error.getErrores()).containsOnlyKeys("email");
        assertThat(error.getErrores().get("email")).containsExactly(CodigoError.OBLIGATORIO);
    }

    @ParameterizedTest(name = "correo \"{0}\"")
    @ValueSource(strings = { "no-es-un-correo", "sin-arroba.es", "a@b", "a b@ejemplo.es", "a@@ejemplo.es", "@ejemplo.es",
            "lucia<script>@ejemplo.es", "lucia@ejemplo.es, otra@ejemplo.es" })
    void preparar_correoConFormatoInvalido_esUnErrorDeValidacion(String email) { // CP-2FA-402
        DatosRegistroInvalidosException error = assertThrows(DatosRegistroInvalidosException.class,
                () -> servicio.preparar(email, CONTEXTO));

        assertThat(error.getErrores().get("email")).containsExactly(CodigoError.FORMATO_INVALIDO);
    }

    @Test
    void preparar_correoDeMasDe254Caracteres_esUnErrorDeLongitud() { // CP-2FA-403
        String largo = "a".repeat(250) + "@ejemplo.es";

        DatosRegistroInvalidosException error = assertThrows(DatosRegistroInvalidosException.class,
                () -> servicio.preparar(largo, CONTEXTO));

        assertThat(error.getErrores().get("email")).containsExactly(CodigoError.LONGITUD_EXCESIVA);
    }

    @Test
    void preparar_conUnCorreoInvalido_noGeneraNiAuditaNada() { // CP-2FA-404
        assertThrows(DatosRegistroInvalidosException.class, () -> servicio.preparar("no-es-un-correo", CONTEXTO));

        verifyNoMoreInteractions(auditoria);
    }

    @Test
    void preparar_audita_conElCorreoNormalizado() { // CP-2FA-405
        servicio.preparar("  LUCIA.torres@ejemplo.es ", CONTEXTO);

        verify(auditoria).registrarRegistroDosFactoresIniciado(CONTEXTO, EMAIL);
    }

    @Test
    void preparar_noEscribeElSecretoEnLosLogsDeLaAplicacion() { // CP-2FA-406
        ServicioRegistroDosFactores conAuditoriaReal = new ServicioRegistroDosFactores(cifrador, totp,
                new AuditoriaSeguridad(), PropiedadesDosFactoresPrueba.acordadas());
        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            PreparacionRegistroDosFactores preparacion = conAuditoriaReal.preparar(EMAIL, CONTEXTO);

            // Solo los logs de la aplicacion: el capturador recoge todo el proceso, incluidos hilos de fondo de otras pruebas
            assertThat(logs.eventos().stream().filter(e -> e.getLoggerName().startsWith("com.esibuy"))
                    .map(e -> e.getFormattedMessage()).toList())
                    .isNotEmpty()
                    .allSatisfy(texto -> assertThat(texto).doesNotContain(preparacion.secreto(), preparacion.secretoCifrado(),
                            preparacion.uri(), EMAIL));
        }
    }

    @Test
    void preparar_siFallaElCifrado_fallaCerradoSinDetalles() { // CP-2FA-407
        CifradorSecretos cifradorQueFalla = mock(CifradorSecretos.class);
        when(cifradorQueFalla.cifrar(any(), anyString())).thenThrow(new IllegalStateException("detalle interno"));
        ServicioRegistroDosFactores conCifradorRoto = new ServicioRegistroDosFactores(cifradorQueFalla, totp, auditoria,
                PropiedadesDosFactoresPrueba.acordadas());

        ServicioNoDisponibleException error = assertThrows(ServicioNoDisponibleException.class,
                () -> conCifradorRoto.preparar(EMAIL, CONTEXTO));

        assertThat(error.getCause()).isNull();
        verifyNoInteractions(auditoria);
    }

    @Test
    void preparacion_elTextoNoMuestraElSecretoNiLaUri() { // CP-2FA-408
        PreparacionRegistroDosFactores preparacion = servicio.preparar(EMAIL, CONTEXTO);

        assertThat(preparacion.toString()).doesNotContain(preparacion.secreto(), preparacion.secretoCifrado(),
                preparacion.uri(), "otpauth");
    }

    @Test
    void preparar_losCorreosConMayusculasYMinusculasDanElMismoDatoAsociado() { // CP-2FA-409
        PreparacionRegistroDosFactores minusculas = servicio.preparar(EMAIL, CONTEXTO);
        PreparacionRegistroDosFactores mayusculas = servicio.preparar(EMAIL.toUpperCase(), CONTEXTO);

        // Los dos se pueden descifrar con el mismo correo normalizado
        assertThat(cifrador.descifrar(minusculas.secretoCifrado(), EMAIL)).hasSize(20);
        assertThat(cifrador.descifrar(mayusculas.secretoCifrado(), EMAIL)).hasSize(20);
    }

    @Test
    void preparar_noDistingueSiElCorreoYaEstaRegistrado() { // CP-2FA-410
        // El servicio no tiene acceso a ningun repositorio: la respuesta no puede depender de si la cuenta existe
        assertThat(ServicioRegistroDosFactores.class.getDeclaredConstructors()).singleElement().satisfies(constructor ->
                assertThat(constructor.getParameterTypes()).containsExactly(CifradorSecretos.class, GeneradorTotp.class,
                        AuditoriaSeguridad.class, PropiedadesDosFactores.class));
    }
}
