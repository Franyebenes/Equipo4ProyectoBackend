package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.esibuy.esibuy_backend.dto.ProductoAltaDTO;
import com.esibuy.esibuy_backend.excepcion.ProductoInvalidoException;
import com.esibuy.esibuy_backend.excepcion.StockModificadoException;
import com.esibuy.esibuy_backend.modelo.Categoria;
import com.esibuy.esibuy_backend.modelo.PrecioProducto;
import com.esibuy.esibuy_backend.modelo.Producto;
import com.esibuy.esibuy_backend.repositorio.RepositorioCategoria;
import com.esibuy.esibuy_backend.repositorio.RepositorioProducto;

/**
 * Gestion de stock y Modificar producto con stockAnterior (la cantidad que vio el vendedor al abrir la pantalla). Si
 * mientras tanto el stock ha cambiado (p. ej. un cliente ha reservado unidades en su carrito), no se pisa la cantidad
 * actual con una antigua.
 */
@ExtendWith(MockitoExtension.class)
class ServicioProductoStockAnteriorTest {

    private static final String ID_VENDEDOR = "64b7f0c2a1b2c3d4e5f60001";
    private static final String ID_PRODUCTO = "64b7f0c2a1b2c3d4e5f60101";
    private static final String ID_HOGAR = "64b7f0c2a1b2c3d4e5f60202";
    private static final Categoria HOGAR = new Categoria(ID_HOGAR, "Hogar", "Muebles y decoración.");

    @Mock
    private RepositorioProducto repositorioProducto;
    @Mock
    private RepositorioCategoria repositorioCategoria;
    @InjectMocks
    private ServicioProductoImpl servicio;

    // Producto con el stock que hay ahora en la base de datos
    private static Producto lamparaConStock(int stock) {
        return Producto.builder()
                .id(ID_PRODUCTO)
                .idVendedor(ID_VENDEDOR)
                .idCategorias(List.of(ID_HOGAR))
                .nombre("Lampara")
                .descripcion("Lampara de sobremesa.")
                .precio(new PrecioProducto(80.0, null, null))
                .stock(stock)
                .visible(true)
                .build();
    }

    private static ProductoAltaDTO datosConPrecioYStock(double precio, int stock) {
        return new ProductoAltaDTO("Lampara", "Lampara de sobremesa.", precio, List.of(ID_HOGAR), stock, null, null,
                null, null, null);
    }

    private void guardarDevuelveElProducto() {
        when(repositorioProducto.save(any(Producto.class))).thenAnswer(invocacion -> invocacion.getArgument(0));
        when(repositorioCategoria.findAllById(any())).thenReturn(List.of(HOGAR));
    }

    private Producto productoGuardado() {
        ArgumentCaptor<Producto> guardado = ArgumentCaptor.forClass(Producto.class);
        verify(repositorioProducto).save(guardado.capture());
        return guardado.getValue();
    }

    @Test
    void cambiarStock_stockSinCambiosDesdeQueLoVio_guardaLaCantidadNueva() {
        // Given: vio 4 y siguen siendo 4
        when(repositorioProducto.findById(ID_PRODUCTO)).thenReturn(Optional.of(lamparaConStock(4)));
        guardarDevuelveElProducto();

        // When
        servicio.cambiarStock(ID_VENDEDOR, ID_PRODUCTO, 10, 4);

        // Then
        assertThat(productoGuardado().getStock()).isEqualTo(10);
    }

    @Test
    void cambiarStock_unClienteReservoMientrasTanto_lanza409ConLaCantidadActualYNoGuarda() {
        // Given: vio 4, pero un cliente ha reservado 1 y ahora quedan 3
        when(repositorioProducto.findById(ID_PRODUCTO)).thenReturn(Optional.of(lamparaConStock(3)));

        // When / Then: no se guardan las 10 calculadas sobre un dato antiguo
        assertThatThrownBy(() -> servicio.cambiarStock(ID_VENDEDOR, ID_PRODUCTO, 10, 4))
                .isInstanceOf(StockModificadoException.class)
                .hasMessage("El stock ha cambiado mientras lo editabas: ahora hay 3 unidades. "
                        + "Revisa la cantidad y vuelve a guardar.")
                .extracting(excepcion -> ((StockModificadoException) excepcion).getStockActual())
                .isEqualTo(3);
        verify(repositorioProducto, never()).save(any());
    }

    @Test
    void cambiarStock_conUnaSolaUnidadActual_loIndicaEnSingular() {
        // Given
        when(repositorioProducto.findById(ID_PRODUCTO)).thenReturn(Optional.of(lamparaConStock(1)));

        // When / Then
        assertThatThrownBy(() -> servicio.cambiarStock(ID_VENDEDOR, ID_PRODUCTO, 10, 4))
                .hasMessageContaining("ahora hay 1 unidad.");
    }

    @Test
    void cambiarStock_sinStockAnterior_guardaLaCantidadIndicada() {
        // Given: un cliente de la API que no envia stockAnterior
        when(repositorioProducto.findById(ID_PRODUCTO)).thenReturn(Optional.of(lamparaConStock(3)));
        guardarDevuelveElProducto();

        // When
        servicio.cambiarStock(ID_VENDEDOR, ID_PRODUCTO, 10, null);

        // Then
        assertThat(productoGuardado().getStock()).isEqualTo(10);
    }

    @Test
    void cambiarStock_porEncimaDelMaximo_lanzaExcepcionYNoConsultaNiGuarda() {
        // Given
        int demasiadas = ServicioProductoImpl.STOCK_MAXIMO + 1;

        // When / Then
        assertThatThrownBy(() -> servicio.cambiarStock(ID_VENDEDOR, ID_PRODUCTO, demasiadas, null))
                .isInstanceOf(ProductoInvalidoException.class)
                .hasMessage(ServicioProductoImpl.MENSAJE_STOCK_MAXIMO);
        verify(repositorioProducto, never()).findById(anyString());
        verify(repositorioProducto, never()).save(any());
    }

    @Test
    void cambiarStock_justoElMaximo_seGuarda() {
        // Given
        when(repositorioProducto.findById(ID_PRODUCTO)).thenReturn(Optional.of(lamparaConStock(4)));
        guardarDevuelveElProducto();

        // When
        servicio.cambiarStock(ID_VENDEDOR, ID_PRODUCTO, ServicioProductoImpl.STOCK_MAXIMO, 4);

        // Then
        assertThat(productoGuardado().getStock()).isEqualTo(ServicioProductoImpl.STOCK_MAXIMO);
    }

    @Test
    void modificarProducto_sinTocarElStockMientrasUnClienteReservaba_guardaLosCambiosYConservaElStockActual() {
        // Given: el formulario cargo 4 unidades; un cliente reservo 1 y quedan 3. El vendedor solo cambia el precio
        when(repositorioProducto.findById(ID_PRODUCTO)).thenReturn(Optional.of(lamparaConStock(3)));
        guardarDevuelveElProducto();

        // When: el formulario envia las 4 que cargo
        servicio.modificarProducto(ID_VENDEDOR, ID_PRODUCTO, datosConPrecioYStock(95.0, 4), 4);

        // Then: se guarda el precio nuevo y no se pisa la reserva del cliente
        Producto guardado = productoGuardado();
        assertThat(guardado.getPrecio().getBase()).isEqualTo(95.0);
        assertThat(guardado.getStock()).isEqualTo(3);
    }

    @Test
    void modificarProducto_cambiandoElStockMientrasUnClienteReservaba_lanza409YNoGuardaNada() {
        // Given: el formulario cargo 4, ahora quedan 3 y el vendedor quiere poner 10
        when(repositorioProducto.findById(ID_PRODUCTO)).thenReturn(Optional.of(lamparaConStock(3)));
        when(repositorioCategoria.findAllById(any())).thenReturn(List.of(HOGAR));

        // When / Then
        ProductoAltaDTO datos = datosConPrecioYStock(95.0, 10);
        assertThatThrownBy(() -> servicio.modificarProducto(ID_VENDEDOR, ID_PRODUCTO, datos, 4))
                .isInstanceOf(StockModificadoException.class);
        verify(repositorioProducto, never()).save(any());
    }

    @Test
    void modificarProducto_stockSinCambiosDesdeQueLoVio_guardaElStockNuevo() {
        // Given
        when(repositorioProducto.findById(ID_PRODUCTO)).thenReturn(Optional.of(lamparaConStock(4)));
        guardarDevuelveElProducto();

        // When
        servicio.modificarProducto(ID_VENDEDOR, ID_PRODUCTO, datosConPrecioYStock(80.0, 10), 4);

        // Then
        assertThat(productoGuardado().getStock()).isEqualTo(10);
    }
}
