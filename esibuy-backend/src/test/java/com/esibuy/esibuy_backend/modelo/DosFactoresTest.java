package com.esibuy.esibuy_backend.modelo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.esibuy.esibuy_backend.modelo.DosFactores.CodigoRecuperacion;
import com.esibuy.esibuy_backend.modelo.DosFactores.Pendiente;
import com.esibuy.esibuy_backend.servicio.GeneradorTotp;

/**
 * Estado del segundo factor de un usuario (HdU 2FA, fase 1). Casos: CP-2FA-89 a CP-2FA-99.
 *
 * DosFactores es el subdocumento "dosFactores" de usuarios. Tiene dos formas: pendiente (la persona ha empezado la
 * activacion y aun no ha confirmado el primer codigo: solo existe el secreto pendiente) y activo (secreto definitivo,
 * ultimo paso aceptado y codigos de recuperacion). Los secretos son siempre el texto cifrado que produce
 * CifradorSecretos ("version:base64url") y los codigos de recuperacion solo existen como hash: nada en claro.
 * Los cambios de estado, que deben ser atomicos, los hace RepositorioDosFactores; esta clase solo representa el estado.
 *
 * Terminada: ya no lleva la etiqueta pendiente-2fa y entra en la regresion.
 */
class DosFactoresTest {

    private static final String CIFRADO = "1:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String HASH_1 = "a".repeat(64);
    private static final String HASH_2 = "b".repeat(64);
    private static final String HASH_3 = "0123456789abcdef".repeat(4);
    private static final Instant AHORA = Instant.parse("2026-10-20T10:00:00Z");

    private static List<CodigoRecuperacion> codigos(String... hashes) {
        List<CodigoRecuperacion> lista = new ArrayList<>();
        for (String hash : hashes) {
            lista.add(CodigoRecuperacion.nuevo(hash));
        }
        return lista;
    }

    // ---------------------------------------------------------------- pendiente

    @Test
    void pendiente_noEstaActivoYSoloTieneElSecretoPendiente() { // CP-2FA-89
        DosFactores dosFactores = DosFactores.pendiente(CIFRADO, AHORA);

        assertThat(dosFactores.isActivo()).isFalse();
        assertThat(dosFactores.getPendiente().getSecretoCifrado()).isEqualTo(CIFRADO);
        assertThat(dosFactores.getPendiente().getCreadoEn()).isEqualTo(AHORA);
        assertThat(dosFactores.getSecretoCifrado()).isNull();
        assertThat(dosFactores.getActivadoEn()).isNull();
        assertThat(dosFactores.getUltimoPaso()).isEqualTo(GeneradorTotp.NINGUN_PASO);
        assertThat(dosFactores.getCodigosRecuperacion()).isEmpty();
        assertThat(dosFactores.codigosDisponibles()).isZero();
    }

    @ParameterizedTest(name = "\"{0}\"")
    @NullAndEmptySource
    @ValueSource(strings = {
            " ",
            "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", // un secreto en Base32, sin cifrar
            "1:", ":AAAA", "x:AAAA", "1:AAAA AAAA", "1:AAAA=", "1:AAAA\nBBBB", "1234567890:AAAA" })
    void pendiente_secretoQueNoEsTextoCifrado_lanzaExcepcion(String secreto) { // CP-2FA-90
        // Garantia de que un secreto en claro no llega nunca a guardarse: solo se admite "version:base64url"
        assertThrows(IllegalArgumentException.class, () -> DosFactores.pendiente(secreto, AHORA));
    }

    @Test
    void pendiente_sinFechaDeCreacion_lanzaExcepcion() { // CP-2FA-90
        assertThrows(IllegalArgumentException.class, () -> DosFactores.pendiente(CIFRADO, null));
    }

    @ParameterizedTest(name = "caducidad {0} s, ahora = creado + {1} s -> caducado {2}")
    @CsvSource({
            "600,0,false",
            "600,599,false",
            "600,600,true", // a los 10 minutos exactos ya no vale, como los tokens de recuperacion
            "600,601,true",
            "600,86400,true" })
    void pendiente_haCaducado_vale_mientrasAhoraSeaAnteriorAlLimite(long caducidad, long transcurrido, boolean esperado) { // CP-2FA-91
        Pendiente pendiente = DosFactores.pendiente(CIFRADO, AHORA).getPendiente();

        assertThat(pendiente.haCaducado(AHORA.plusSeconds(transcurrido), Duration.ofSeconds(caducidad)))
                .isEqualTo(esperado);
    }

    // ---------------------------------------------------------------- activo

    @Test
    void activo_guardaElSecretoElUltimoPasoYLosCodigos() { // CP-2FA-92
        DosFactores dosFactores = DosFactores.activo(CIFRADO, AHORA, 58_000_000L, codigos(HASH_1, HASH_2, HASH_3));

        assertThat(dosFactores.isActivo()).isTrue();
        assertThat(dosFactores.getSecretoCifrado()).isEqualTo(CIFRADO);
        assertThat(dosFactores.getActivadoEn()).isEqualTo(AHORA);
        assertThat(dosFactores.getUltimoPaso()).isEqualTo(58_000_000L);
        assertThat(dosFactores.getPendiente()).isNull();
        assertThat(dosFactores.getCodigosRecuperacion()).extracting(CodigoRecuperacion::getHash)
                .containsExactly(HASH_1, HASH_2, HASH_3);
    }

    @Test
    void activo_sinPasoAceptadoTodavia_usaNingunPaso() { // CP-2FA-92
        assertThat(DosFactores.activo(CIFRADO, AHORA, GeneradorTotp.NINGUN_PASO, codigos(HASH_1)).getUltimoPaso())
                .isEqualTo(GeneradorTotp.NINGUN_PASO);
    }

    @Test
    void activo_datosInvalidos_lanzaExcepcion() { // CP-2FA-93
        List<CodigoRecuperacion> unCodigo = codigos(HASH_1);

        assertThrows(IllegalArgumentException.class, () -> DosFactores.activo(null, AHORA, 1, unCodigo));
        assertThrows(IllegalArgumentException.class,
                () -> DosFactores.activo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", AHORA, 1, unCodigo));
        assertThrows(IllegalArgumentException.class, () -> DosFactores.activo(CIFRADO, null, 1, unCodigo));
        assertThrows(IllegalArgumentException.class, () -> DosFactores.activo(CIFRADO, AHORA, 1, null));
        // Un paso menor que NINGUN_PASO no existe
        assertThrows(IllegalArgumentException.class, () -> DosFactores.activo(CIFRADO, AHORA, -2, unCodigo));
        // Mas de 20 codigos no los admite el esquema de la base de datos
        List<CodigoRecuperacion> demasiados = codigos(hashesDistintos(21));
        assertThrows(IllegalArgumentException.class, () -> DosFactores.activo(CIFRADO, AHORA, 1, demasiados));
    }

    private static String[] hashesDistintos(int cantidad) {
        String[] hashes = new String[cantidad];
        for (int i = 0; i < cantidad; i++) {
            hashes[i] = String.format("%064x", i + 1);
        }
        return hashes;
    }

    @Test
    void codigosDisponibles_cuentaLosQueNoSeHanUsado() { // CP-2FA-94
        List<CodigoRecuperacion> lista = codigos(HASH_1, HASH_2, HASH_3);
        DosFactores dosFactores = DosFactores.activo(CIFRADO, AHORA, 1, lista);

        assertThat(dosFactores.codigosDisponibles()).isEqualTo(3);
        assertThat(lista).allSatisfy(c -> assertThat(c.isUsado()).isFalse());
        assertThat(lista).allSatisfy(c -> assertThat(c.getUsadoEn()).isNull());
    }

    @Test
    void getCodigosRecuperacion_noSePuedeModificarDesdeFuera() { // CP-2FA-95
        List<CodigoRecuperacion> original = codigos(HASH_1, HASH_2);
        DosFactores dosFactores = DosFactores.activo(CIFRADO, AHORA, 1, original);

        assertThrows(UnsupportedOperationException.class,
                () -> dosFactores.getCodigosRecuperacion().add(CodigoRecuperacion.nuevo(HASH_3)));
        assertThrows(UnsupportedOperationException.class, () -> dosFactores.getCodigosRecuperacion().remove(0));
        // y cambiar la lista que se paso al crearlo tampoco le afecta
        original.add(CodigoRecuperacion.nuevo(HASH_3));
        assertThat(dosFactores.getCodigosRecuperacion()).hasSize(2);
    }

    // ---------------------------------------------------------------- codigo de recuperacion

    @Test
    void codigoNuevo_noEstaUsado() { // CP-2FA-96
        CodigoRecuperacion codigo = CodigoRecuperacion.nuevo(HASH_3);

        assertThat(codigo.getHash()).isEqualTo(HASH_3);
        assertThat(codigo.isUsado()).isFalse();
        assertThat(codigo.getUsadoEn()).isNull();
    }

    // Los valores con repeat() no son constantes, y las anotaciones solo admiten constantes: por eso es un MethodSource
    static Stream<String> hashesInvalidos() {
        return Stream.of(
                null, "", "abc",
                "A".repeat(64), // mayusculas: el hash es hexadecimal en minusculas
                "a".repeat(63), "a".repeat(65), "g".repeat(64),
                "ABCD-EFGH-2345-6723"); // un codigo en claro, que es lo que nunca debe guardarse
    }

    @ParameterizedTest
    @MethodSource("hashesInvalidos")
    void codigoNuevo_queNoEsUnHashSha256Hexadecimal_lanzaExcepcion(String hash) { // CP-2FA-97
        assertThrows(IllegalArgumentException.class, () -> CodigoRecuperacion.nuevo(hash));
    }

    // ---------------------------------------------------------------- el usuario

    @Test
    void usuarioNuevo_noTieneDosFactores() { // CP-2FA-99
        // Las cuentas actuales (y las nuevas) siguen con un solo factor hasta que su dueno active el 2FA
        Usuario usuario = new Usuario("ana@ejemplo.es", "hash", Rol.CLIENTE,
                PerfilUsuario.builder().nombre("Ana").apellidos("Garcia").build());

        assertThat(usuario.getDosFactores()).isNull();
        assertThat(usuario.tieneDosFactoresActivo()).isFalse();
        assertThat(usuario.toString()).doesNotContainIgnoringCase("factor");
    }

    // ---------------------------------------------------------------- nada en claro en los textos

    @Test
    void toString_noMuestraSecretosNiHashes() { // CP-2FA-98
        DosFactores activo = DosFactores.activo(CIFRADO, AHORA, 1, codigos(HASH_1, HASH_3));
        DosFactores pendiente = DosFactores.pendiente(CIFRADO, AHORA);

        for (String texto : new String[] { activo.toString(), pendiente.toString(), pendiente.getPendiente().toString(),
                activo.getCodigosRecuperacion().get(0).toString() }) {
            assertThat(texto).doesNotContain(CIFRADO).doesNotContain("AAAAAAAA").doesNotContain(HASH_1)
                    .doesNotContain(HASH_3);
        }
    }
}
