package com.esibuy.esibuy_backend.modelo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ListaProductosTest {

    @Test
    void favoritos_esPorDefectoYEmpiezaVacia() {
        ListaProductos favoritos = ListaProductos.favoritos("u1");

        assertThat(favoritos.isPorDefecto()).isTrue();
        assertThat(favoritos.getNombre()).isEqualTo(ListaProductos.NOMBRE_FAVORITOS);
        assertThat(favoritos.getIdProductos()).isEmpty();
    }

    @Test
    void anadirProducto_dosVeces_noLoDuplica() {
        ListaProductos lista = ListaProductos.nueva("u1", "Moda");

        assertThat(lista.anadirProducto("p1")).isTrue();
        assertThat(lista.anadirProducto("p1")).isFalse();
        assertThat(lista.getIdProductos()).containsExactly("p1");
    }

    @Test
    void renombrar_listaPorDefecto_lanzaYNoCambia() {
        ListaProductos favoritos = ListaProductos.favoritos("u1");

        assertThatThrownBy(() -> favoritos.renombrar("Otra")).isInstanceOf(IllegalStateException.class);
        assertThat(favoritos.getNombre()).isEqualTo(ListaProductos.NOMBRE_FAVORITOS);
    }

    @Test
    void getIdProductos_noPermiteModificarLaListaDesdeFuera() {
        ListaProductos lista = ListaProductos.nueva("u1", "Moda");

        assertThatThrownBy(() -> lista.getIdProductos().add("p1")).isInstanceOf(UnsupportedOperationException.class);
    }
}