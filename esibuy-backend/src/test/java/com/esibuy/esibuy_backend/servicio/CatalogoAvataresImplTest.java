package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Catalogo de avatares definido en configuracion.
 */
class CatalogoAvataresImplTest {

    private static final List<String> DISPONIBLES = List.of("avatar-01", "avatar-02", "avatar-03");

    private final CatalogoAvataresImpl catalogo = new CatalogoAvataresImpl(
            new PropiedadesAvatares(DISPONIBLES, "defecto-cliente", "defecto-vendedor"));

    @Test
    void listarAvatares_devuelveLosDisponiblesEnElOrdenConfigurado() {
        // When
        List<String> avatares = catalogo.listarAvatares();

        // Then
        assertThat(avatares).containsExactlyElementsOf(DISPONIBLES);
    }

    @Test
    void esAvatarValido_avatarDelCatalogo_esValido() {
        // When / Then
        assertThat(catalogo.esAvatarValido("avatar-02")).isTrue();
    }

    @ParameterizedTest(name = "avatar no valido: \"{0}\"")
    @NullAndEmptySource
    @ValueSource(strings = {"avatar-99", "AVATAR-01", "https://evil.example.com/a.png", "../avatar-01"})
    void esAvatarValido_avatarFueraDelCatalogo_noEsValido(String avatar) {
        // When / Then
        assertThat(catalogo.esAvatarValido(avatar)).isFalse();
    }

    @Test
    void avataresPorDefecto_clienteYVendedor_sonLosConfigurados() {
        // When / Then
        assertThat(catalogo.avatarPorDefectoCliente()).isEqualTo("defecto-cliente");
        assertThat(catalogo.avatarPorDefectoVendedor()).isEqualTo("defecto-vendedor");
    }

    @Test
    void propiedades_mismoAvatarPorDefectoParaClienteYVendedor_fallaAlArrancar() {
        // When / Then
        assertThrows(IllegalStateException.class,
                () -> new PropiedadesAvatares(DISPONIBLES, "defecto", "defecto"));
    }

    @Test
    void propiedades_sinAvataresDisponibles_fallaAlArrancar() {
        // Given
        List<String> ninguno = List.of();

        // When / Then
        assertThrows(IllegalStateException.class,
                () -> new PropiedadesAvatares(ninguno, "defecto-cliente", "defecto-vendedor"));
    }

    @ParameterizedTest(name = "identificador no valido: \"{0}\"")
    @ValueSource(strings = {"../etc/passwd", "Avatar-01", "avatar 01", "avatar.png"})
    void propiedades_identificadorConCaracteresNoPermitidos_fallaAlArrancar(String idInvalido) {
        // Given
        List<String> disponibles = List.of(idInvalido);

        // When / Then
        assertThrows(IllegalStateException.class,
                () -> new PropiedadesAvatares(disponibles, "defecto-cliente", "defecto-vendedor"));
    }
}
