package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.esibuy.esibuy_backend.modelo.Producto;

/**
 * HU Buscar en catalogo propio: busqueda por nombre completo o parcial, sin distinguir mayusculas ni tildes; sin
 * criterio se ve todo el catalogo.
 */
class FiltroCatalogoTest {

    private static Producto conNombre(String nombre) {
        return Producto.builder().nombre(nombre).build();
    }

    @Test
    void sinFiltros_admiteCualquierProducto() {
        // When / Then
        assertThat(FiltroCatalogo.sinFiltros().admite(conNombre("Lámpara de pie"))).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void busquedaVacia_admiteTodoElCatalogo(String busqueda) {
        // When / Then: limpiar la busqueda vuelve a mostrar todos los productos
        assertThat(new FiltroCatalogo(busqueda).admite(conNombre("Lámpara de pie"))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Lámpara de pie", "lámpara", "LAMPARA", "de pie", "  pie  ", "ampa"})
    void busqueda_porNombreCompletoOParcialSinMayusculasNiTildes_loEncuentra(String busqueda) {
        // When / Then
        assertThat(new FiltroCatalogo(busqueda).admite(conNombre("Lámpara de pie"))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"mesa", "lampara de techo", "pies"})
    void busqueda_queNoAparece_noLoEncuentra(String busqueda) {
        // When / Then
        assertThat(new FiltroCatalogo(busqueda).admite(conNombre("Lámpara de pie"))).isFalse();
    }

    @Test
    void busqueda_productoSinNombre_noLoEncuentra() {
        // When / Then
        assertThat(new FiltroCatalogo("lampara").admite(conNombre(null))).isFalse();
    }

    @Test
    void normalizar_quitaTildesDieresisYMayusculas() {
        // When / Then
        assertThat(FiltroCatalogo.normalizar("  Pingüino ÑANDÚ Electrónica ")).isEqualTo("pinguino nandu electronica");
    }
}
