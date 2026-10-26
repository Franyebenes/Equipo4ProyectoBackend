package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.esibuy.esibuy_backend.dto.ProductoClienteDTO;
import com.esibuy.esibuy_backend.modelo.PrecioProducto;
import com.esibuy.esibuy_backend.modelo.Producto;
import com.esibuy.esibuy_backend.repositorio.RepositorioCategoria;
import com.esibuy.esibuy_backend.repositorio.RepositorioProducto;
import com.esibuy.esibuy_backend.repositorio.RepositorioValoracion;

/** HU-31.1 Productos de la lista de favoritos: en el orden pedido y solo los que el cliente puede ver. */
@ExtendWith(MockitoExtension.class)
class ServicioCatalogoFavoritosTest {

    private static final String ID_TAZA = "64b7f0c2a1b2c3d4e5f60101";
    private static final String ID_VASO = "64b7f0c2a1b2c3d4e5f60102";
    private static final String ID_OCULTO = "64b7f0c2a1b2c3d4e5f60103";

    @Mock
    private RepositorioProducto repositorioProducto;
    @Mock
    private RepositorioCategoria repositorioCategoria;
    @Mock
    private RepositorioValoracion repositorioValoracion;
    @InjectMocks
    private ServicioCatalogo servicio;

    private static Producto producto(String id, String nombre, boolean activo) {
        return Producto.builder().id(id).nombre(nombre).precio(new PrecioProducto(10.0, null, null)).stock(0)
                .visible(activo).build();
    }

    @Test
    void productosActivos_enElOrdenPedidoYSinLosInactivosNiLosBorrados() {
        // Given: MongoDB los devuelve en otro orden y uno de los pedidos ya no existe
        when(repositorioProducto.findAllById(any())).thenReturn(List.of(
                producto(ID_TAZA, "Taza", true), producto(ID_OCULTO, "Oculto", false), producto(ID_VASO, "Vaso", true)));

        // When
        List<ProductoClienteDTO> productos = servicio.productosActivos(
                List.of(ID_VASO, ID_OCULTO, "64b7f0c2a1b2c3d4e5f60199", ID_TAZA), false);

        // Then
        assertThat(productos).extracting(ProductoClienteDTO::nombre).containsExactly("Vaso", "Taza");
    }

    @Test
    void productosActivos_sinIds_noConsultaNada() {
        assertThat(servicio.productosActivos(List.of(), false)).isEmpty();
        verifyNoInteractions(repositorioProducto, repositorioCategoria, repositorioValoracion);
    }
}
