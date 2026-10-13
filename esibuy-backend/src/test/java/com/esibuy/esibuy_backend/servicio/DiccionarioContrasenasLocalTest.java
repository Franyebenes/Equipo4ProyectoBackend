package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.text.Normalizer;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Diccionario local cargado desde los ficheros reales de src/main/resources/seguridad.
 */
class DiccionarioContrasenasLocalTest {

    private static final String COMUNES = "seguridad/contrasenas-comunes.txt";
    private static final String FILTRADAS = "seguridad/contrasenas-filtradas.txt";

    private final DiccionarioContrasenasLocal diccionario = new DiccionarioContrasenasLocal(COMUNES, FILTRADAS);

    @ParameterizedTest(name = "comun: \"{0}\"")
    @ValueSource(strings = {"12345678", "password1", "qwertyuiop12", "123456789012"})
    void esComun_contrasenasDeLaLista_sonComunes(String contrasena) {
        // When / Then
        assertThat(diccionario.esComun(contrasena)).isTrue();
    }

    @Test
    void esComun_contrasenaConEnie_seComparaEnMinusculasYNfc() {
        // Given: el validador pregunta siempre en minusculas y NFC; la lista tiene "contraseña123"
        String enNfd = Normalizer.normalize("CONTRASEÑA123", Normalizer.Form.NFD);
        String consulta = Normalizer.normalize(enNfd, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);

        // When / Then
        assertThat(diccionario.esComun(consulta)).isTrue();
    }

    @Test
    void esComun_contrasenaQueNoEstaEnLaLista_noEsComun() {
        // When / Then
        assertThat(diccionario.esComun("tren-azul-lluvia-77")).isFalse();
    }

    @Test
    void esComun_lineaDeComentarioDelFichero_noSeCargaComoContrasena() {
        // Given: segunda linea de comentario del fichero, en minusculas
        String comentario = "#";

        // When / Then
        assertThat(diccionario.esComun(comentario)).isFalse();
    }

    @Test
    void estaFiltrada_contrasenaDeLaLista_distingueMayusculas() {
        // When / Then
        assertThat(diccionario.estaFiltrada("Qwerty123456")).isTrue();
        assertThat(diccionario.estaFiltrada("qWERTY123456")).isFalse();
    }

    @Test
    void constructor_ficheroInexistente_fallaAlArrancar() {
        // When / Then
        assertThrows(IllegalStateException.class,
                () -> new DiccionarioContrasenasLocal("seguridad/no-existe.txt", FILTRADAS));
    }
}
