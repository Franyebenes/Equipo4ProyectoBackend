package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import com.esibuy.esibuy_backend.modelo.Carrito;
import com.esibuy.esibuy_backend.modelo.Producto;
import com.esibuy.esibuy_backend.repositorio.RepositorioCarrito;
import com.esibuy.esibuy_backend.repositorio.RepositorioProducto;

/** Carritos abandonados: pasadas 24 horas sin tocarlos se vacian y sus unidades vuelven al stock. */
@ExtendWith(MockitoExtension.class)
class ServicioCarritoCaducidadTest {

    private static final String ID_CLIENTE = "64b000000000000000000001";
    private static final String ID_TECLADO = "64b000000000000000000002";
    private static final String ID_RATON = "64b000000000000000000003";
    private static final Instant AHORA = Instant.parse("2026-10-25T12:00:00Z");

    @Mock
    private RepositorioCarrito repositorioCarrito;
    @Mock
    private RepositorioProducto repositorioProducto;
    @Mock
    private MongoTemplate mongoTemplate;
    @InjectMocks
    private ServicioCarrito servicio;

    @Test
    void carritoCaducadoSeVaciaYDevuelveTodasSusUnidadesAlStock() {
        // Given: un carrito caducado con 2 teclados y 3 ratones; despues ya no quedan mas
        Carrito caducado = new Carrito(ID_CLIENTE);
        caducado.anadir(ID_TECLADO, 2);
        caducado.anadir(ID_RATON, 3);
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), eq(Carrito.class)))
                .thenReturn(caducado, (Carrito) null);
        // When
        int vaciados = servicio.liberarCarritosCaducados(AHORA);
        // Then
        assertThat(vaciados).isEqualTo(1);
        assertThat(incrementosDeStock()).containsExactly(2, 3);
    }

    @Test
    void sinCarritosCaducadosNoSeTocaElStock() {
        // Given
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), eq(Carrito.class))).thenReturn(null);
        // When
        int vaciados = servicio.liberarCarritosCaducados(AHORA);
        // Then
        assertThat(vaciados).isZero();
        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class), eq(Producto.class));
    }

    @Test
    void soloSeVacianLosCarritosConProductosSinTocarDesdeHaceMasDe24Horas() {
        // Given
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), eq(Carrito.class))).thenReturn(null);
        // When
        servicio.liberarCarritosCaducados(AHORA);
        // Then
        ArgumentCaptor<Query> consulta = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).findAndModify(consulta.capture(), any(Update.class), eq(Carrito.class));
        Document filtro = consulta.getValue().getQueryObject();
        assertThat(((Document) filtro.get("ultimaModificacion")).get("$lt"))
                .isEqualTo(AHORA.minus(ServicioCarrito.CADUCIDAD_CARRITO));
        assertThat(((Document) filtro.get("lineas.0")).get("$exists")).isEqualTo(true);
    }

    @Test
    void losCarritosSinFechaEmpiezanAContarDesdeAhora() {
        // Given: carritos guardados antes de que existiera la caducidad
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), eq(Carrito.class))).thenReturn(null);
        // When
        servicio.liberarCarritosCaducados(AHORA);
        // Then: se les pone la fecha de ahora, sin vaciarlos
        ArgumentCaptor<Query> consulta = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> cambio = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateMulti(consulta.capture(), cambio.capture(), eq(Carrito.class));
        assertThat(((Document) consulta.getValue().getQueryObject().get("ultimaModificacion")).get("$exists"))
                .isEqualTo(false);
        assertThat(((Document) cambio.getValue().getUpdateObject().get("$set")).get("ultimaModificacion"))
                .isEqualTo(AHORA);
    }

    /** Lo que se ha sumado al stock en cada devolucion, en orden. */
    private List<Object> incrementosDeStock() {
        ArgumentCaptor<Update> cambios = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate, times(2)).updateFirst(any(Query.class), cambios.capture(), eq(Producto.class));
        return cambios.getAllValues().stream()
                .map(cambio -> ((Document) cambio.getUpdateObject().get("$inc")).get("stock"))
                .toList();
    }
}
