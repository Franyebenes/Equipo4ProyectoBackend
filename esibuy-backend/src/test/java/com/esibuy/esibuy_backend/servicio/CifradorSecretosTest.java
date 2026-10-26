package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Cifrado del secreto TOTP en reposo (HdU 2FA, fase 0). Casos: CP-2FA-36 a CP-2FA-50.
 *
 * El secreto no se puede guardar con hash, porque el servidor necesita el valor original para recalcular el codigo;
 * por eso se cifra con AES-256-GCM. Formato guardado: "version:base64url(IV de 12 bytes + cifrado + etiqueta de 16)".
 * La version permite rotar la clave sin perder los secretos ya guardados. El id del usuario se usa como dato asociado
 * autenticado: un secreto copiado a otra cuenta no se puede descifrar. Las claves son de 32 bytes.
 *
 * Terminada: ya no lleva la etiqueta pendiente-2fa y entra en la regresion.
 */
class CifradorSecretosTest {

    private static final String USUARIO = "665f1c2e9b1d4a3c8e7f0a11";
    private static final byte[] SECRETO = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    private static byte[] clave(int semilla) {
        byte[] clave = new byte[32];
        for (int i = 0; i < clave.length; i++) {
            clave[i] = (byte) (semilla + i);
        }
        return clave;
    }

    private static CifradorSecretos cifradorConClaveUno() {
        return new CifradorSecretos(Map.of(1, clave(1)), 1);
    }

    private static byte[] cargaUtil(String cifrado) {
        return Base64.getUrlDecoder().decode(cifrado.substring(cifrado.indexOf(':') + 1));
    }

    // ---------------------------------------------------------------- formato y ida y vuelta

    @Test
    void cifrar_formatoVersionDosPuntosYBase64UrlSinRelleno() { // CP-2FA-36
        String cifrado = cifradorConClaveUno().cifrar(SECRETO, USUARIO);

        assertThat(cifrado).matches("^1:[A-Za-z0-9_-]+$");
    }

    @Test
    void cifrar_laCargaSonIvDe12BytesMasElCifradoMasEtiquetaDe16() { // CP-2FA-36
        String cifrado = cifradorConClaveUno().cifrar(SECRETO, USUARIO);

        assertThat(cargaUtil(cifrado)).hasSize(12 + SECRETO.length + 16);
    }

    @ParameterizedTest(name = "{0} bytes")
    @ValueSource(ints = { 1, 16, 20, 32, 100 })
    void cifrarYDescifrar_devuelveElMismoSecreto(int longitud) { // CP-2FA-37
        byte[] secreto = new byte[longitud];
        Arrays.fill(secreto, (byte) 0x5A);
        CifradorSecretos cifrador = cifradorConClaveUno();

        assertThat(cifrador.descifrar(cifrador.cifrar(secreto, USUARIO), USUARIO)).isEqualTo(secreto);
    }

    @Test
    void cifrar_elMismoSecretoDosVeces_daCifradosDistintosQueSeDescifranIgual() { // CP-2FA-38
        CifradorSecretos cifrador = cifradorConClaveUno();

        String uno = cifrador.cifrar(SECRETO, USUARIO);
        String otro = cifrador.cifrar(SECRETO, USUARIO);

        assertThat(uno).isNotEqualTo(otro);
        assertThat(cifrador.descifrar(uno, USUARIO)).isEqualTo(SECRETO);
        assertThat(cifrador.descifrar(otro, USUARIO)).isEqualTo(SECRETO);
    }

    @Test
    void cifrar_milVeces_nuncaRepiteElIv() { // CP-2FA-38
        CifradorSecretos cifrador = cifradorConClaveUno();
        Set<String> ivs = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            byte[] carga = cargaUtil(cifrador.cifrar(SECRETO, USUARIO));
            ivs.add(Base64.getEncoder().encodeToString(Arrays.copyOfRange(carga, 0, 12)));
        }

        assertThat(ivs).hasSize(1000);
    }

    @Test
    void cifrar_elResultadoNoContieneElSecretoEnClaro() { // CP-2FA-39
        String cifrado = cifradorConClaveUno().cifrar(SECRETO, USUARIO);

        assertThat(cifrado).doesNotContain(Base32.codificar(SECRETO))
                .doesNotContain(Base64.getEncoder().encodeToString(SECRETO))
                .doesNotContain(Base64.getUrlEncoder().withoutPadding().encodeToString(SECRETO))
                .doesNotContain("12345678901234567890");
    }

    // ---------------------------------------------------------------- integridad

    @Test
    void descifrar_cualquierByteModificado_lanzaExcepcion() { // CP-2FA-40
        CifradorSecretos cifrador = cifradorConClaveUno();
        String cifrado = cifrador.cifrar(SECRETO, USUARIO);
        byte[] carga = cargaUtil(cifrado);

        // Se prueba un byte del IV, uno del cifrado y uno de la etiqueta
        for (int posicion : new int[] { 0, 11, 12, 12 + SECRETO.length - 1, carga.length - 1 }) {
            byte[] alterada = carga.clone();
            alterada[posicion] ^= 0x01;
            String alterado = "1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(alterada);

            assertThrows(IllegalArgumentException.class, () -> cifrador.descifrar(alterado, USUARIO),
                    "byte " + posicion);
        }
    }

    @Test
    void descifrar_cargaTruncadaOConBytesDeMas_lanzaExcepcion() { // CP-2FA-40
        CifradorSecretos cifrador = cifradorConClaveUno();
        byte[] carga = cargaUtil(cifrador.cifrar(SECRETO, USUARIO));

        String truncada = "1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(
                Arrays.copyOf(carga, carga.length - 1));
        byte[] larga = Arrays.copyOf(carga, carga.length + 1);
        String conDeMas = "1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(larga);

        assertThrows(IllegalArgumentException.class, () -> cifrador.descifrar(truncada, USUARIO));
        assertThrows(IllegalArgumentException.class, () -> cifrador.descifrar(conDeMas, USUARIO));
    }

    @Test
    void descifrar_conOtroUsuarioComoDatoAsociado_lanzaExcepcion() { // CP-2FA-41
        CifradorSecretos cifrador = cifradorConClaveUno();
        String cifrado = cifrador.cifrar(SECRETO, USUARIO);

        // Un secreto copiado a otra cuenta no se puede usar
        assertThrows(IllegalArgumentException.class, () -> cifrador.descifrar(cifrado, "665f1c2e9b1d4a3c8e7f0a12"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { " " })
    void cifrar_sinDatoAsociado_lanzaExcepcion(String asociado) { // CP-2FA-41
        assertThrows(IllegalArgumentException.class, () -> cifradorConClaveUno().cifrar(SECRETO, asociado));
        assertThrows(IllegalArgumentException.class, () -> cifradorConClaveUno().descifrar("1:AAAA", asociado));
    }

    @Test
    void descifrar_conOtraClaveDeLaMismaVersion_lanzaExcepcion() { // CP-2FA-42
        String cifrado = cifradorConClaveUno().cifrar(SECRETO, USUARIO);
        CifradorSecretos conOtraClave = new CifradorSecretos(Map.of(1, clave(77)), 1);

        assertThrows(IllegalArgumentException.class, () -> conOtraClave.descifrar(cifrado, USUARIO));
    }

    @Test
    void cifrar_secretoNuloOVacio_lanzaExcepcion() { // CP-2FA-43
        assertThrows(IllegalArgumentException.class, () -> cifradorConClaveUno().cifrar(null, USUARIO));
        assertThrows(IllegalArgumentException.class, () -> cifradorConClaveUno().cifrar(new byte[0], USUARIO));
    }

    @ParameterizedTest(name = "\"{0}\"")
    @NullAndEmptySource
    @ValueSource(strings = {
            "sin-dos-puntos", "1:", ":AAAA", "x:AAAA", "-1:AAAA", "0:AAAA", "1:!!!!", "1:AAAA", "1:AAAA:AAAA",
            "9:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" })
    void descifrar_formatoInvalidoOVersionDesconocida_lanzaExcepcion(String cifrado) { // CP-2FA-44
        assertThrows(IllegalArgumentException.class, () -> cifradorConClaveUno().descifrar(cifrado, USUARIO));
    }

    @Test
    void descifrar_elMensajeDeErrorNoFiltraNiElCifradoNiElUsuario() { // CP-2FA-45
        CifradorSecretos cifrador = cifradorConClaveUno();
        String cifrado = cifrador.cifrar(SECRETO, USUARIO);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> cifrador.descifrar(cifrado, "665f1c2e9b1d4a3c8e7f0a12"));

        assertThat(String.valueOf(error.getMessage())).doesNotContain(cifrado).doesNotContain(USUARIO)
                .doesNotContain("665f1c2e9b1d4a3c8e7f0a12");
        // y la causa interna tampoco lleva el material
        assertThat(error.getCause()).isNull();
    }

    // ---------------------------------------------------------------- rotacion de claves

    @Test
    void conVariasClaves_cifraConLaActivaYDescifraConCualquiera() { // CP-2FA-46
        String antiguo = cifradorConClaveUno().cifrar(SECRETO, USUARIO);
        Map<Integer, byte[]> claves = new HashMap<>();
        claves.put(1, clave(1));
        claves.put(2, clave(2));
        CifradorSecretos rotado = new CifradorSecretos(claves, 2);

        String nuevo = rotado.cifrar(SECRETO, USUARIO);

        assertThat(nuevo).startsWith("2:");
        assertThat(rotado.descifrar(nuevo, USUARIO)).isEqualTo(SECRETO);
        // Lo cifrado antes de rotar sigue siendo legible
        assertThat(rotado.descifrar(antiguo, USUARIO)).isEqualTo(SECRETO);
    }

    @Test
    void usaLaClaveActiva_distingueLosSecretosAntiguos() { // CP-2FA-47
        String antiguo = cifradorConClaveUno().cifrar(SECRETO, USUARIO);
        Map<Integer, byte[]> claves = new HashMap<>();
        claves.put(1, clave(1));
        claves.put(2, clave(2));
        CifradorSecretos rotado = new CifradorSecretos(claves, 2);

        assertThat(rotado.usaLaClaveActiva(antiguo)).isFalse();
        assertThat(rotado.usaLaClaveActiva(rotado.cifrar(SECRETO, USUARIO))).isTrue();
    }

    // ---------------------------------------------------------------- construccion

    @Test
    void crear_clavesInvalidas_lanzaExcepcion() { // CP-2FA-48
        assertThrows(IllegalArgumentException.class, () -> new CifradorSecretos(null, 1));
        assertThrows(IllegalArgumentException.class, () -> new CifradorSecretos(Map.of(), 1));
        assertThrows(IllegalArgumentException.class, () -> new CifradorSecretos(Map.of(1, new byte[16]), 1));
        assertThrows(IllegalArgumentException.class, () -> new CifradorSecretos(Map.of(1, new byte[24]), 1));
        assertThrows(IllegalArgumentException.class, () -> new CifradorSecretos(Map.of(1, new byte[33]), 1));
        assertThrows(IllegalArgumentException.class, () -> new CifradorSecretos(Map.of(0, clave(1)), 0));
        assertThrows(IllegalArgumentException.class, () -> new CifradorSecretos(Map.of(-1, clave(1)), -1));

        Map<Integer, byte[]> conNulo = new HashMap<>();
        conNulo.put(1, null);
        assertThrows(IllegalArgumentException.class, () -> new CifradorSecretos(conNulo, 1));
    }

    @Test
    void crear_versionActivaQueNoEstaEntreLasClaves_lanzaExcepcion() { // CP-2FA-48
        assertThrows(IllegalArgumentException.class, () -> new CifradorSecretos(Map.of(1, clave(1)), 2));
    }

    @Test
    void crear_copiaLasClaves_cambiarElArrayDespuesNoAfecta() { // CP-2FA-49
        byte[] original = clave(1);
        CifradorSecretos cifrador = new CifradorSecretos(Map.of(1, original), 1);
        String cifrado = cifrador.cifrar(SECRETO, USUARIO);

        Arrays.fill(original, (byte) 0);

        assertThat(cifrador.descifrar(cifrado, USUARIO)).isEqualTo(SECRETO);
    }

    @Test
    void toString_noMuestraMaterialDeClave() { // CP-2FA-49
        byte[] material = clave(1);
        String texto = new CifradorSecretos(Map.of(1, material), 1).toString();

        assertThat(texto).doesNotContain(Base64.getEncoder().encodeToString(material))
                .doesNotContain(Base64.getUrlEncoder().withoutPadding().encodeToString(material))
                .doesNotContain(Arrays.toString(material));
    }

    // ---------------------------------------------------------------- concurrencia

    @Test
    void usadoDesdeVariosHilos_todosLosCifradosSeDescifranBien() throws Exception { // CP-2FA-50
        // Cipher no es seguro entre hilos: el cifrador debe crear uno por llamada
        CifradorSecretos cifrador = cifradorConClaveUno();
        ExecutorService ejecutor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> tareas = new ArrayList<>();
            for (int i = 0; i < 400; i++) {
                tareas.add(() -> Arrays.equals(SECRETO, cifrador.descifrar(cifrador.cifrar(SECRETO, USUARIO), USUARIO)));
            }
            for (Future<Boolean> resultado : ejecutor.invokeAll(tareas)) {
                assertThat(resultado.get(30, TimeUnit.SECONDS)).isTrue();
            }
        } finally {
            ejecutor.shutdownNow();
        }
    }
}
