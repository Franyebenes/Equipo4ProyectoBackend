package com.esibuy.esibuy_backend.servicio;

import java.text.Normalizer;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.esibuy.esibuy_backend.excepcion.CodigoError;
import static com.esibuy.esibuy_backend.util.Constantes.LONGITUD_MAXIMA;
import static com.esibuy.esibuy_backend.util.Constantes.LONGITUD_MINIMA;
import com.esibuy.esibuy_backend.util.DiccionarioContrasenasFalso;

/**
 * Pruebas unitarias puras de la politica de contrasenas (sin mocks: diccionario falso en memoria).
 */
class ValidadorContrasenaTest {

    private static final String CONTRASENA_VALIDA = "Tren-Azul-Lluvia-77";

    /** Un emoji ocupa dos unidades UTF-16 pero es un unico caracter; para la politica cuenta como caracter especial. */
    private static final String EMOJI = "😀";

    private static final DatosPersonalesContrasena DATOS_CLIENTE = new DatosPersonalesContrasena(
            "Ana", "Garc\u00eda L\u00f3pez", "ana.garcia@ejemplo.es", null);

    private static final DatosPersonalesContrasena DATOS_VENDEDOR = new DatosPersonalesContrasena(
            "Luis", "P\u00e9rez Mora", "luis.perez@tienda.es", "Tienda Norte");

    private ValidadorContrasena validador;

    @BeforeEach
    void prepararValidador() {
        validador = new ValidadorContrasena(new DiccionarioContrasenasFalso());
    }

    /** Genera una contrasena de la longitud pedida que no es comun ni contiene datos personales. */
    private static String contrasenaDeLongitud(int longitud) {
        String patron = "Tk9$mQ";
        StringBuilder contrasena = new StringBuilder();
        while (contrasena.length() < longitud) {
            contrasena.append(patron);
        }
        return contrasena.substring(0, longitud);
    }

    // ------------------------------------------------------------------ CP-PWD-01

    @Test
    void validar_contrasenaQueCumpleTodaLaPolitica_noDevuelveErrores() { // CP-PWD-01
        // Given
        String contrasena = CONTRASENA_VALIDA;

        // When
        Set<CodigoError> errores = validador.validar(contrasena, DATOS_CLIENTE);

        // Then
        assertThat(errores).isEmpty();
    }

    // ------------------------------------------------------------------ CP-PWD-02 (longitud)

    @Test
    void validar_contrasenaUnCaracterMasCortaQueElMinimo_devuelveContrasenaCorta() { // CP-PWD-02
        // Given
        String contrasena = contrasenaDeLongitud(LONGITUD_MINIMA - 1);

        // When
        Set<CodigoError> errores = validador.validar(contrasena, DATOS_CLIENTE);

        // Then
        assertThat(errores).containsExactly(CodigoError.CONTRASENA_CORTA);
    }

    @Test
    void validar_contrasenaConLongitudMinimaExacta_esValida() { // CP-PWD-02
        // Given
        String contrasena = contrasenaDeLongitud(LONGITUD_MINIMA);

        // When
        Set<CodigoError> errores = validador.validar(contrasena, DATOS_CLIENTE);

        // Then
        assertThat(errores).isEmpty();
    }

    @Test
    void validar_contrasenaConLongitudMaximaExacta_esValida() { // CP-PWD-02
        // Given
        String contrasena = contrasenaDeLongitud(LONGITUD_MAXIMA);

        // When
        Set<CodigoError> errores = validador.validar(contrasena, DATOS_CLIENTE);

        // Then
        assertThat(errores).isEmpty();
    }

    @Test
    void validar_contrasenaUnCaracterMasLargaQueElMaximo_devuelveContrasenaLarga() { // CP-PWD-02
        // Given
        String contrasena = contrasenaDeLongitud(LONGITUD_MAXIMA + 1);

        // When
        Set<CodigoError> errores = validador.validar(contrasena, DATOS_CLIENTE);

        // Then
        assertThat(errores).containsExactly(CodigoError.CONTRASENA_LARGA);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"            ", "\t\t\t"})
    void validar_contrasenaNulaVaciaOSoloEspacios_devuelveObligatorio(String contrasena) { // CP-PWD-02
        // Given: contrasena nula, vacia o formada solo por espacios en blanco

        // When
        Set<CodigoError> errores = validador.validar(contrasena, DATOS_CLIENTE);

        // Then
        assertThat(errores).contains(CodigoError.OBLIGATORIO);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"            ", "\t\t\t"})
    void validar_contrasenaNulaVaciaOSoloEspacios_noAcumulaErroresDeComposicion(String contrasena) { // CP-PWD-02
        // Given: sin contrasena no tiene sentido pedir ademas numero, mayuscula, minuscula o caracter especial

        // When
        Set<CodigoError> errores = validador.validar(contrasena, DATOS_CLIENTE);

        // Then
        assertThat(errores).containsExactly(CodigoError.OBLIGATORIO);
    }

    // ------------------------------------------------------------------ CP-PWD-03 y CP-PWD-04 (comunes)

    @ParameterizedTest
    @ValueSource(strings = {"12345678", "Password1", "qwertyui"})
    void validar_contrasenasComunesDelDocumento_devuelveContrasenaComun(String contrasena) { // CP-PWD-03
        // Given: las tres contrasenas citadas en el documento de requisitos

        // When
        Set<CodigoError> errores = validador.validar(contrasena, DATOS_CLIENTE);

        // Then
        assertThat(errores).contains(CodigoError.CONTRASENA_COMUN);
    }

    @Test
    void validar_contrasenaComunConLongitudSuficiente_rechazaPorComunYNoPorCorta() { // CP-PWD-03
        // Given: comun pero con 12 caracteres, asi que la longitud por si sola no la bloquea
        String contrasena = "qwertyuiop12";

        // When
        Set<CodigoError> errores = validador.validar(contrasena, DATOS_CLIENTE);

        // Then
        assertThat(errores)
                .contains(CodigoError.CONTRASENA_COMUN)
                .doesNotContain(CodigoError.CONTRASENA_CORTA);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PASSWORD1", "PassWord1", "qwertyUI", "QWERTYUIOP12"})
    void validar_contrasenaComunConOtraCapitalizacion_devuelveContrasenaComun(String contrasena) { // CP-PWD-04
        // Given: contrasenas comunes escritas con mayusculas distintas

        // When
        Set<CodigoError> errores = validador.validar(contrasena, DATOS_CLIENTE);

        // Then
        assertThat(errores).contains(CodigoError.CONTRASENA_COMUN);
    }

    // ------------------------------------------------------------------ CP-PWD-05 (filtradas)

    @Test
    void validar_contrasenaPresenteEnElDiccionarioDeFiltradas_devuelveContrasenaFiltrada() { // CP-PWD-05
        // Given
        String contrasena = DiccionarioContrasenasFalso.CONTRASENA_FILTRADA;

        // When
        Set<CodigoError> errores = validador.validar(contrasena, DATOS_CLIENTE);

        // Then
        assertThat(errores).containsExactly(CodigoError.CONTRASENA_FILTRADA);
    }

    // ------------------------------------------------------------------ CP-PWD-06, 07 y 08 (datos personales)

    @ParameterizedTest
    @ValueSource(strings = {
            "Xk-Ana-Lluvia-7788",            // nombre
            "Xk-Garcia-Lluvia-7788",         // primer apellido (sin acento)
            "Xk-L\u00f3pez-Lluvia-7788",     // segundo apellido
            "Xk-ana.garcia-Lluvia-7788",     // parte local del email
            "ana.garcia@ejemplo.es-Xk77"     // email completo
    })
    void validar_contrasenaConDatosPersonalesDelCliente_devuelveDatosPersonales(String contrasena) { // CP-PWD-06
        // Given: contrasenas que incluyen nombre, apellidos o email del propio usuario

        // When
        Set<CodigoError> errores = validador.validar(contrasena, DATOS_CLIENTE);

        // Then
        assertThat(errores).contains(CodigoError.CONTRASENA_CON_DATOS_PERSONALES);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Xk-TIENDA-Lluvia-7788", "Xk-norte-Lluvia-7788"})
    void validar_contrasenaConNombreComercialDelVendedor_devuelveDatosPersonales(String contrasena) { // CP-PWD-07
        // Given: vendedor cuyo nombre comercial es "Tienda Norte"

        // When
        Set<CodigoError> errores = validador.validar(contrasena, DATOS_VENDEDOR);

        // Then
        assertThat(errores).contains(CodigoError.CONTRASENA_CON_DATOS_PERSONALES);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Xk-esibuy-Lluvia-7788", "Xk-ESIBuy-Lluvia-7788"})
    void validar_contrasenaConElNombreDeLaAplicacion_devuelveDatosPersonales(String contrasena) { // CP-PWD-07
        // Given: contrasena que incluye el nombre de la aplicacion

        // When
        Set<CodigoError> errores = validador.validar(contrasena, DATOS_CLIENTE);

        // Then
        assertThat(errores).contains(CodigoError.CONTRASENA_CON_DATOS_PERSONALES);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Xk-GARCIA-77-Lluvia",           // mayusculas
            "Xk-garcia-77-Lluvia",           // sin acento
            "Xk-GARC\u00cdA-77-Lluvia"       // mayusculas con acento
    })
    void validar_datosPersonalesConOtraCapitalizacionOAcentos_devuelveDatosPersonales(String contrasena) { // CP-PWD-08
        // Given: el apellido registrado es "Garcia Lopez" con tilde en la "i"

        // When
        Set<CodigoError> errores = validador.validar(contrasena, DATOS_CLIENTE);

        // Then
        assertThat(errores).contains(CodigoError.CONTRASENA_CON_DATOS_PERSONALES);
    }

    @Test
    void validar_nombreMuyCortoDentroDeLaContrasena_noLaRechazaPorDatosPersonales() { // CP-PWD-09
        // Given: nombre y apellido de 2 letras, por debajo de la longitud minima de dato personal
        DatosPersonalesContrasena datosCortos =
                new DatosPersonalesContrasena("Al", "Ro", "al.ro@mail.es", null);
        String contrasena = "Tren-Al-Ro-Lluvia-77";

        // When
        Set<CodigoError> errores = validador.validar(contrasena, datosCortos);

        // Then
        assertThat(errores).isEmpty();
    }

    // ------------------------------------------------------------------ CP-PWD-10 (varios errores)

    @Test
    void validar_contrasenaConVariosFallosAlaVez_devuelveTodosLosErrores() { // CP-PWD-10
        // Given: "password1" es corta, comun, contiene el nombre "Pass" y no tiene mayuscula ni caracter especial
        DatosPersonalesContrasena datos =
                new DatosPersonalesContrasena("Pass", "Test", "pass.test@ejemplo.es", null);

        // When
        Set<CodigoError> errores = validador.validar("password1", datos);

        // Then
        assertThat(errores).containsExactlyInAnyOrder(
                CodigoError.CONTRASENA_CORTA,
                CodigoError.CONTRASENA_COMUN,
                CodigoError.CONTRASENA_CON_DATOS_PERSONALES,
                CodigoError.CONTRASENA_SIN_MAYUSCULA,
                CodigoError.CONTRASENA_SIN_CARACTER_ESPECIAL);
    }

    // ------------------------------------------------------------------ CP-PWD-11 (Unicode NFC / NFD)

    @Test
    void validar_mismaContrasenaEnFormaNfcYNfd_devuelveElMismoResultado() { // CP-PWD-11
        // Given: "e con tilde" como un unico caracter (NFC) y como "e" + tilde combinada (NFD)
        String enNfc = "Tren-Caf\u00e9-Lluvia-77";
        String enNfd = Normalizer.normalize(enNfc, Normalizer.Form.NFD);

        // When
        Set<CodigoError> erroresNfc = validador.validar(enNfc, DATOS_CLIENTE);
        Set<CodigoError> erroresNfd = validador.validar(enNfd, DATOS_CLIENTE);

        // Then
        assertThat(enNfd).isNotEqualTo(enNfc);
        assertThat(erroresNfd).isEqualTo(erroresNfc).isEmpty();
    }

    @Test
    void validar_contrasenaFiltradaEscritaEnNfd_seRechazaIgualQueEnNfc() { // CP-PWD-11
        // Given: una contrasena del diccionario de filtradas, pero con los caracteres descompuestos
        String enNfd = Normalizer.normalize(DiccionarioContrasenasFalso.CONTRASENA_FILTRADA, Normalizer.Form.NFD);

        // When
        Set<CodigoError> errores = validador.validar(enNfd, DATOS_CLIENTE);

        // Then
        assertThat(errores).contains(CodigoError.CONTRASENA_FILTRADA);
    }

    // ------------------------------------------------------------------ CP-PWD-12 (emojis / multibyte)

    @Test
    void validar_contrasenaConEmojis_noLanzaExcepcionYSeAcepta() { // CP-PWD-12
        // Given
        String contrasena = "Tren-Azul-\uD83D\uDE00\uD83D\uDE00-Lluvia-77";

        // When
        Set<CodigoError> errores = assertDoesNotThrow(() -> validador.validar(contrasena, DATOS_CLIENTE));

        // Then
        assertThat(errores).isEmpty();
    }

    @Test
    void validar_contrasenaDeEmojisConMaximoDeCaracteres_cuentaPuntosDeCodigoYSeAcepta() { // CP-PWD-12
        // Given: 3 caracteres para cumplir la composicion y 125 emojis (250 unidades UTF-16): 128 caracteres reales
        String contrasena = "Aa1" + EMOJI.repeat(LONGITUD_MAXIMA - 3);

        // When
        Set<CodigoError> errores = validador.validar(contrasena, DATOS_CLIENTE);

        // Then
        assertThat(errores).isEmpty();
    }

    @Test
    void validar_contrasenaDeEmojisConUnCaracterMasDelMaximo_devuelveContrasenaLarga() { // CP-PWD-12
        // Given: 129 caracteres reales (la composicion es correcta, solo falla la longitud)
        String contrasena = "Aa1" + EMOJI.repeat(LONGITUD_MAXIMA - 2);

        // When
        Set<CodigoError> errores = validador.validar(contrasena, DATOS_CLIENTE);

        // Then
        assertThat(errores).containsExactly(CodigoError.CONTRASENA_LARGA);
    }

    @Test
    void validar_contrasenaSoloDeEmojis_cuentaComoCaracterEspecialPeroNoComoLetraNiNumero() { // CP-PWD-12
        // Given: 12 emojis, la longitud minima exacta
        String contrasena = EMOJI.repeat(LONGITUD_MINIMA);

        // When
        Set<CodigoError> errores = validador.validar(contrasena, DATOS_CLIENTE);

        // Then: no falta el caracter especial (el emoji lo es) pero si el resto
        assertThat(errores).containsExactlyInAnyOrder(
                CodigoError.CONTRASENA_SIN_NUMERO,
                CodigoError.CONTRASENA_SIN_MAYUSCULA,
                CodigoError.CONTRASENA_SIN_MINUSCULA);
    }

    // ------------------------------------------------------------------ CP-PWD-15 (al menos un numero)

    @Test
    void validar_contrasenaSinNingunNumero_devuelveSinNumero() { // CP-PWD-15
        // Given: tiene mayuscula, minuscula y caracter especial, pero ningun digito
        String contrasena = "Tren-Azul-Lluvia-Sol";

        // When
        Set<CodigoError> errores = validador.validar(contrasena, DATOS_CLIENTE);

        // Then
        assertThat(errores).containsExactly(CodigoError.CONTRASENA_SIN_NUMERO);
    }

    @Test
    void validar_contrasenaConUnSoloNumero_esValida() { // CP-PWD-15
        // Given: un unico digito basta
        String contrasena = "Tren-Azul-Lluvia-S7";

        // When
        Set<CodigoError> errores = validador.validar(contrasena, DATOS_CLIENTE);

        // Then
        assertThat(errores).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"٣", "７", "²"})
    void validar_digitosQueNoSonAscii_noCuentanComoNumero(String digito) { // CP-PWD-15
        // Given: un digito arabigo-indico, uno de ancho completo y un superindice: la regla pide los digitos 0-9
        String contrasena = "Tren-Azul-Lluvia-" + digito;

        // When
        Set<CodigoError> errores = validador.validar(contrasena, DATOS_CLIENTE);

        // Then
        assertThat(errores).containsExactly(CodigoError.CONTRASENA_SIN_NUMERO);
    }

    // ------------------------------------------------------------------ CP-PWD-16 (al menos un caracter especial)

    @Test
    void validar_contrasenaSinCaracterEspecial_devuelveSinCaracterEspecial() { // CP-PWD-16
        // Given: tiene mayuscula, minuscula y numero, pero solo letras y digitos
        String contrasena = "TrenAzulLluviaSol77";

        // When
        Set<CodigoError> errores = validador.validar(contrasena, DATOS_CLIENTE);

        // Then
        assertThat(errores).containsExactly(CodigoError.CONTRASENA_SIN_CARACTER_ESPECIAL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-", "_", ".", ",", ";", ":", "!", "?", "@", "#", "$", "%", "&", "*", "+", "=", "/",
            "\\", "(", ")", "[", "]", "<", ">", "|", "~", "^", "`", "'", "\"",
            "€", "£", "¿", "¡", "·"})
    void validar_cadaSignoDePuntuacionOSimbolo_cuentaComoCaracterEspecial(String especial) { // CP-PWD-16
        // Given: el resto de la politica se cumple; el unico especial es el signo que se prueba
        String contrasena = "TrenAzul" + especial + "Lluvia77";

        // When
        Set<CodigoError> errores = validador.validar(contrasena, DATOS_CLIENTE);

        // Then
        assertThat(errores).isEmpty();
    }

    @Test
    void validar_emojiComoUnicoCaracterEspecial_cuentaComoCaracterEspecial() { // CP-PWD-16
        // Given
        String contrasena = "TrenAzulLluvia77" + EMOJI;

        // When
        Set<CodigoError> errores = validador.validar(contrasena, DATOS_CLIENTE);

        // Then
        assertThat(errores).isEmpty();
    }

    @Test
    void validar_contrasenaSinEmoji_noExigeElEmoji() { // CP-PWD-16
        // Given: cumple con un guion y sin ningun emoji
        String contrasena = "Tren-Azul-Lluvia-77";

        // When
        Set<CodigoError> errores = validador.validar(contrasena, DATOS_CLIENTE);

        // Then
        assertThat(errores).doesNotContain(CodigoError.CONTRASENA_SIN_CARACTER_ESPECIAL).isEmpty();
    }

    @Test
    void validar_espaciosSinOtroCaracterEspecial_noCuentanComoCaracterEspecial() { // CP-PWD-16
        // Given: los espacios se permiten (frases) pero no sustituyen al caracter especial
        String contrasena = "Tren Azul Lluvia 77";

        // When
        Set<CodigoError> errores = validador.validar(contrasena, DATOS_CLIENTE);

        // Then
        assertThat(errores).containsExactly(CodigoError.CONTRASENA_SIN_CARACTER_ESPECIAL);
    }

    @Test
    void validar_frasePermitidaConEspaciosYCaracterEspecial_esValida() { // CP-PWD-16
        // Given
        String contrasena = "Tren azul Lluvia 77!";

        // When
        Set<CodigoError> errores = validador.validar(contrasena, DATOS_CLIENTE);

        // Then
        assertThat(errores).isEmpty();
    }

    @Test
    void validar_letrasConAcentoYEnie_noCuentanComoCaracterEspecial() { // CP-PWD-16
        // Given: "e con acento" y "n con tilde" son letras, no simbolos
        String contrasena = "TrenCaféLluviañ77";

        // When
        Set<CodigoError> errores = validador.validar(contrasena, DATOS_CLIENTE);

        // Then
        assertThat(errores).containsExactly(CodigoError.CONTRASENA_SIN_CARACTER_ESPECIAL);
    }

    // ------------------------------------------------------------------ CP-PWD-17 (al menos una mayuscula)

    @Test
    void validar_contrasenaSinMayusculas_devuelveSinMayuscula() { // CP-PWD-17
        // Given
        String contrasena = "tren-azul-lluvia-77";

        // When
        Set<CodigoError> errores = validador.validar(contrasena, DATOS_CLIENTE);

        // Then
        assertThat(errores).containsExactly(CodigoError.CONTRASENA_SIN_MAYUSCULA);
    }

    @Test
    void validar_contrasenaConUnaSolaMayuscula_esValida() { // CP-PWD-17
        // Given
        String contrasena = "tren-azul-lluvia-7S";

        // When
        Set<CodigoError> errores = validador.validar(contrasena, DATOS_CLIENTE);

        // Then
        assertThat(errores).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"É", "Ñ", "Ü"})
    void validar_mayusculaConAcentoOEnie_cuentaComoMayuscula(String mayuscula) { // CP-PWD-17
        // Given: la unica mayuscula es una letra acentuada o la "N con tilde"
        String contrasena = "tren-azul-lluvia-77" + mayuscula;

        // When
        Set<CodigoError> errores = validador.validar(contrasena, DATOS_CLIENTE);

        // Then
        assertThat(errores).isEmpty();
    }

    @Test
    void validar_mayusculaAcentuadaEnFormaNfd_cuentaComoMayuscula() { // CP-PWD-17
        // Given: "E mayuscula con acento" descompuesta (E + acento combinado)
        String enNfd = Normalizer.normalize("tren-azul-Éxito-77", Normalizer.Form.NFD);

        // When
        Set<CodigoError> errores = validador.validar(enNfd, DATOS_CLIENTE);

        // Then
        assertThat(errores).isEmpty();
    }

    // ------------------------------------------------------------------ CP-PWD-18 (al menos una minuscula)

    @Test
    void validar_contrasenaSinMinusculas_devuelveSinMinuscula() { // CP-PWD-18
        // Given
        String contrasena = "TREN-AZUL-LLUVIA-77";

        // When
        Set<CodigoError> errores = validador.validar(contrasena, DATOS_CLIENTE);

        // Then
        assertThat(errores).containsExactly(CodigoError.CONTRASENA_SIN_MINUSCULA);
    }

    @Test
    void validar_contrasenaConUnaSolaMinuscula_esValida() { // CP-PWD-18
        // Given
        String contrasena = "TREN-AZUL-LLUVIA-7s";

        // When
        Set<CodigoError> errores = validador.validar(contrasena, DATOS_CLIENTE);

        // Then
        assertThat(errores).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"é", "ñ", "ü"})
    void validar_minusculaConAcentoOEnie_cuentaComoMinuscula(String minuscula) { // CP-PWD-18
        // Given: la unica minuscula es una letra acentuada o la "n con tilde"
        String contrasena = "TREN-AZUL-LLUVIA-77" + minuscula;

        // When
        Set<CodigoError> errores = validador.validar(contrasena, DATOS_CLIENTE);

        // Then
        assertThat(errores).isEmpty();
    }

    // ------------------------------------------------------------------ CP-PWD-19 (combinaciones)

    @Test
    void validar_contrasenaSoloDeMayusculas_devuelveTodoLoQueLeFalta() { // CP-PWD-19
        // Given: 12 letras mayusculas
        String contrasena = "ABCDEFGHIJKL";

        // When
        Set<CodigoError> errores = validador.validar(contrasena, DATOS_CLIENTE);

        // Then
        assertThat(errores).containsExactlyInAnyOrder(
                CodigoError.CONTRASENA_SIN_NUMERO,
                CodigoError.CONTRASENA_SIN_CARACTER_ESPECIAL,
                CodigoError.CONTRASENA_SIN_MINUSCULA);
    }

    @Test
    void validar_contrasenaSoloDeNumeros_devuelveTodoLoQueLeFalta() { // CP-PWD-19
        // Given: 12 digitos
        String contrasena = "123456789012";

        // When
        Set<CodigoError> errores = validador.validar(contrasena, DATOS_CLIENTE);

        // Then
        assertThat(errores).containsExactlyInAnyOrder(
                CodigoError.CONTRASENA_SIN_MAYUSCULA,
                CodigoError.CONTRASENA_SIN_MINUSCULA,
                CodigoError.CONTRASENA_SIN_CARACTER_ESPECIAL);
    }

    @Test
    void validar_contrasenaCortaYSinComposicion_devuelveLongitudYComposicionALaVez() { // CP-PWD-19
        // Given
        String contrasena = "abc";

        // When
        Set<CodigoError> errores = validador.validar(contrasena, DATOS_CLIENTE);

        // Then
        assertThat(errores).containsExactlyInAnyOrder(
                CodigoError.CONTRASENA_CORTA,
                CodigoError.CONTRASENA_SIN_NUMERO,
                CodigoError.CONTRASENA_SIN_CARACTER_ESPECIAL,
                CodigoError.CONTRASENA_SIN_MAYUSCULA);
    }

    @Test
    void validar_unSoloCaracterDeCadaClase_bastaParaCumplirLaComposicion() { // CP-PWD-19
        // Given: una mayuscula, nueve minusculas, un numero y un caracter especial (12 caracteres)
        String contrasena = "Aaaaaaaaaa1!";

        // When
        Set<CodigoError> errores = validador.validar(contrasena, DATOS_CLIENTE);

        // Then
        assertThat(errores).isEmpty();
    }

    @Test
    void validar_contrasenaComunQueCumpleLaComposicion_sigueRechazadaPorComun() { // CP-PWD-19
        // Given: "Qwertyuiop12" tiene mayuscula, minuscula y numero (le falta el especial) y en minusculas es comun
        String contrasena = "Qwertyuiop12";

        // When
        Set<CodigoError> errores = validador.validar(contrasena, DATOS_CLIENTE);

        // Then: la composicion no sustituye al diccionario
        assertThat(errores).containsExactlyInAnyOrder(
                CodigoError.CONTRASENA_COMUN,
                CodigoError.CONTRASENA_SIN_CARACTER_ESPECIAL);
    }

    @Test
    void validar_alfabetoSinMayusculasNiMinusculas_noPuedeCumplirLaRegla() { // CP-PWD-19
        // Given: 10 ideogramas chinos, un numero y un caracter especial: no hay letras con mayuscula ni minuscula
        String contrasena = "你好世界你好世界你好1!";

        // When
        Set<CodigoError> errores = validador.validar(contrasena, DATOS_CLIENTE);

        // Then
        assertThat(errores).containsExactlyInAnyOrder(
                CodigoError.CONTRASENA_SIN_MAYUSCULA,
                CodigoError.CONTRASENA_SIN_MINUSCULA);
    }

    // CP-PWD-13 (los mensajes de error no incluyen la contrasena) y CP-PWD-14 (repeticion distinta)
    // se verifican en ServicioRegistroValidacionCamposTest, porque dependen de la excepcion y del DTO.
}