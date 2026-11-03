package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.esibuy.esibuy_backend.modelo.PrecioProducto;
import com.esibuy.esibuy_backend.modelo.Producto;

/**
 * HU Buscar en catalogo propio: por nombre completo o parcial, sin distinguir mayusculas ni tildes.
 * HU Filtrar catalogo propio: por categoria, estado y rango de precio, combinables entre si y con la busqueda.
 * Sin criterios (o con los criterios limpios) se ve todo el catalogo.
 */
class FiltroCatalogoTest {

    private static final String ID_HOGAR = "64b7f0c2a1b2c3d4e5f60202";
    private static final String ID_MODA = "64b7f0c2a1b2c3d4e5f60201";
    private static final String ID_OPTICA = "64b7f0c2a1b2c3d4e5f60203";

    // Lampara de pie: Hogar y Moda, 40 euros, activa
    private static final Producto LAMPARA = Producto.builder()
            .nombre("Lámpara de pie")
            .idCategorias(List.of(ID_HOGAR, ID_MODA))
            .precio(new PrecioProducto(40.0, null, null))
            .visible(true)
            .build();

    // Gafas de sol: 120 euros con un 15 % de descuento, asi que los clientes pagan 102 euros
    private static final Producto GAFAS = Producto.builder()
            .nombre("Gafas de sol")
            .precio(new PrecioProducto(120.0, 15.0, null))
            .visible(true)
            .build();

    private static FiltroCatalogo porNombre(String busqueda) {
        return new FiltroCatalogo(busqueda, null, null, null, null);
    }

    // ------------------------------------------------------------------ Sin criterios

    @Test
    void sinFiltros_admiteCualquierProducto() {
        // When / Then
        assertThat(FiltroCatalogo.sinFiltros().admite(LAMPARA)).isTrue();
        assertThat(FiltroCatalogo.sinFiltros().admite(Producto.builder().build())).isTrue();
    }

    // ------------------------------------------------------------------ Buscar por nombre

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void busquedaVacia_admiteTodoElCatalogo(String busqueda) {
        // When / Then: limpiar la busqueda vuelve a mostrar todos los productos
        assertThat(porNombre(busqueda).admite(LAMPARA)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Lámpara de pie", "lámpara", "LAMPARA", "de pie", "  pie  ", "ampa"})
    void busqueda_porNombreCompletoOParcialSinMayusculasNiTildes_loEncuentra(String busqueda) {
        // When / Then
        assertThat(porNombre(busqueda).admite(LAMPARA)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"mesa", "lampara de techo", "pies"})
    void busqueda_queNoAparece_noLoEncuentra(String busqueda) {
        // When / Then
        assertThat(porNombre(busqueda).admite(LAMPARA)).isFalse();
    }

    @Test
    void busqueda_productoSinNombre_noLoEncuentra() {
        // When / Then
        assertThat(porNombre("lampara").admite(Producto.builder().build())).isFalse();
    }

    @Test
    void normalizar_quitaTildesDieresisYMayusculas() {
        // When / Then
        assertThat(FiltroCatalogo.normalizar("  Pingüino ÑANDÚ Electrónica ")).isEqualTo("pinguino nandu electronica");
    }

    // ------------------------------------------------------------------ Filtrar por categoria

    @ParameterizedTest
    @ValueSource(strings = {ID_HOGAR, ID_MODA})
    void categoria_deUnaDeSusCategorias_loAdmite(String idCategoria) {
        // When / Then: un producto con varias categorias aparece al filtrar por cualquiera de ellas
        assertThat(new FiltroCatalogo(null, idCategoria, null, null, null).admite(LAMPARA)).isTrue();
    }

    @Test
    void categoria_queNoTiene_noLoAdmite() {
        // When / Then
        assertThat(new FiltroCatalogo(null, ID_OPTICA, null, null, null).admite(LAMPARA)).isFalse();
        assertThat(new FiltroCatalogo(null, ID_OPTICA, null, null, null).admite(Producto.builder().build())).isFalse();
    }

    // ------------------------------------------------------------------ Filtrar por estado

    @ParameterizedTest
    @CsvSource({
        "true,  true,  true",
        "true,  false, false",
        "false, false, true",
        "false, true,  false",
    })
    void estado_soloAdmiteLosDelEstadoElegido(boolean activo, boolean visible, boolean admitido) {
        // Given
        Producto producto = Producto.builder().nombre("Bolso").visible(visible).build();

        // When / Then
        assertThat(new FiltroCatalogo(null, null, activo, null, null).admite(producto)).isEqualTo(admitido);
    }

    @Test
    void estado_productoSinCampoVisible_cuentaComoInactivo() {
        // Given
        Producto producto = Producto.builder().nombre("Bolso").build();

        // When / Then
        assertThat(new FiltroCatalogo(null, null, false, null, null).admite(producto)).isTrue();
        assertThat(new FiltroCatalogo(null, null, true, null, null).admite(producto)).isFalse();
    }

    // ------------------------------------------------------------- Filtrar por rango de precio (con descuento)

    @ParameterizedTest
    @CsvSource(nullValues = "-", value = {
        "40,  -,   true",
        "41,  -,   false",
        "-,   40,  true",
        "-,   39.99, false",
        "10,  50,  true",
        "40,  40,  true",
        "45,  50,  false",
        "50,  10,  false",
    })
    void precio_admiteSoloLosDelRangoConLosLimitesIncluidos(Double minimo, Double maximo, boolean admitido) {
        // When / Then
        assertThat(new FiltroCatalogo(null, null, null, minimo, maximo).admite(LAMPARA)).isEqualTo(admitido);
    }

    @ParameterizedTest
    @CsvSource(nullValues = "-", value = {
        "-,   110, true",
        "-,   102, true",
        "-,   101.99, false",
        "102, -,   true",
        "110, -,   false",
        "100, 105, true",
        "115, 125, false",
    })
    void precio_conDescuento_seFiltraPorLoQuePaganLosClientes(Double minimo, Double maximo, boolean admitido) {
        // When / Then: cuenta el precio con descuento (102), no el precio base (120)
        assertThat(new FiltroCatalogo(null, null, null, minimo, maximo).admite(GAFAS)).isEqualTo(admitido);
    }

    @Test
    void precio_conDescuento_redondeaACentimosComoElCarrito() {
        // Given: 10 euros con un 70 % son 3 euros, pero en coma flotante la cuenta da 3.0000000000000004
        Producto vela = Producto.builder().precio(new PrecioProducto(10.0, 70.0, null)).build();

        // When / Then: redondeado a centimos, el limite exacto se incluye
        assertThat(new FiltroCatalogo(null, null, null, null, 3.0).admite(vela)).isTrue();
    }

    @Test
    void precio_elDescuentoPremiumNoCuenta() {
        // Given: el descuento premium solo lo tienen los clientes premium; el filtro usa el de todos
        Producto bolso = Producto.builder().precio(new PrecioProducto(100.0, null, 30.0)).build();

        // When / Then
        assertThat(new FiltroCatalogo(null, null, null, null, 80.0).admite(bolso)).isFalse();
        assertThat(new FiltroCatalogo(null, null, null, 100.0, 100.0).admite(bolso)).isTrue();
    }

    @Test
    void precio_productoSinPrecio_noLoAdmiteSiHayRango() {
        // When / Then
        assertThat(new FiltroCatalogo(null, null, null, 0.0, null).admite(Producto.builder().build())).isFalse();
    }

    // ------------------------------------------------------------------ Varios filtros a la vez

    @Test
    void variosFiltros_tieneQueCumplirlosTodos() {
        // When / Then: todos se cumplen
        assertThat(new FiltroCatalogo("lampara", ID_HOGAR, true, 30.0, 50.0).admite(LAMPARA)).isTrue();
        // y basta con que falle uno para que no aparezca
        assertThat(new FiltroCatalogo("mesa", ID_HOGAR, true, 30.0, 50.0).admite(LAMPARA)).isFalse();
        assertThat(new FiltroCatalogo("lampara", ID_OPTICA, true, 30.0, 50.0).admite(LAMPARA)).isFalse();
        assertThat(new FiltroCatalogo("lampara", ID_HOGAR, false, 30.0, 50.0).admite(LAMPARA)).isFalse();
        assertThat(new FiltroCatalogo("lampara", ID_HOGAR, true, 50.0, 60.0).admite(LAMPARA)).isFalse();
    }
}
