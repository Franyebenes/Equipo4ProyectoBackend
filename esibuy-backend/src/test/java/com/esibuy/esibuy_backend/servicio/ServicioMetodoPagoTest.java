package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.esibuy.esibuy_backend.dto.MetodoPagoAltaDTO;
import com.esibuy.esibuy_backend.dto.MetodoPagoDTO;
import com.esibuy.esibuy_backend.excepcion.MetodoPagoInvalidoException;
import com.esibuy.esibuy_backend.excepcion.MetodoPagoNoEncontradoException;
import com.esibuy.esibuy_backend.modelo.MetodoPago;
import com.esibuy.esibuy_backend.repositorio.RepositorioMetodoPago;

/** HU-24.1 Ver, HU-24.2 Anadir y HU-24.3 Eliminar metodos de pago. */
@ExtendWith(MockitoExtension.class)
class ServicioMetodoPagoTest {

    private static final String ID_CLIENTE = "64b000000000000000000001";
    private static final String ID_METODO = "64b000000000000000000050";
    private static final MetodoPagoAltaDTO TARJETA_VALIDA =
            new MetodoPagoAltaDTO("Carlos Ruiz", "4111 1111 1111 4242", "12/28", "123");

    @Mock
    private RepositorioMetodoPago repositorio;
    @InjectMocks
    private ServicioMetodoPago servicio;

    // ---------- HU-24.1 Ver ----------

    @Test
    void listarDevuelveLosMetodosDelClienteDelMasAntiguoAlMasNuevo() {
        // Given: el repositorio los devuelve desordenados
        MetodoPago nuevo = new MetodoPago(ID_CLIENTE, "Carlos", "2222", "01/29", Instant.parse("2026-05-01T00:00:00Z"));
        MetodoPago antiguo = new MetodoPago(ID_CLIENTE, "Carlos", "1111", "01/28", Instant.parse("2026-01-01T00:00:00Z"));
        when(repositorio.buscarPorCliente(new ObjectId(ID_CLIENTE))).thenReturn(List.of(nuevo, antiguo));
        // When
        List<MetodoPagoDTO> metodos = servicio.listar(ID_CLIENTE);
        // Then
        assertThat(metodos).extracting(MetodoPagoDTO::ultimosDigitos).containsExactly("1111", "2222");
    }

    @Test
    void listarSinMetodosDevuelveListaVacia() {
        when(repositorio.buscarPorCliente(new ObjectId(ID_CLIENTE))).thenReturn(List.of());
        assertThat(servicio.listar(ID_CLIENTE)).isEmpty();
    }

    // ---------- HU-24.2 Anadir ----------

    @Test
    void anadirGuardaSoloLosCuatroUltimosDigitosYNuncaElNumeroNiElCvv() {
        // Given
        when(repositorio.contarPorCliente(new ObjectId(ID_CLIENTE))).thenReturn(0L);
        when(repositorio.save(any(MetodoPago.class))).thenAnswer(inv -> inv.getArgument(0));
        // When
        MetodoPagoDTO resultado = servicio.anadir(ID_CLIENTE, TARJETA_VALIDA);
        // Then
        ArgumentCaptor<MetodoPago> captor = ArgumentCaptor.forClass(MetodoPago.class);
        verify(repositorio).save(captor.capture());
        MetodoPago guardado = captor.getValue();
        assertThat(guardado.getIdCliente()).isEqualTo(ID_CLIENTE);
        assertThat(guardado.getTitular()).isEqualTo("Carlos Ruiz");
        assertThat(guardado.getUltimosDigitos()).isEqualTo("4242");
        assertThat(guardado.getCaducidad()).isEqualTo("12/28");
        assertThat(resultado.ultimosDigitos()).isEqualTo("4242");
    }

    @Test
    void anadirAceptaElNumeroConGuionesYQuitaEspaciosDelTitular() {
        // Given
        when(repositorio.contarPorCliente(new ObjectId(ID_CLIENTE))).thenReturn(0L);
        when(repositorio.save(any(MetodoPago.class))).thenAnswer(inv -> inv.getArgument(0));
        // When
        MetodoPagoDTO resultado = servicio.anadir(ID_CLIENTE,
                new MetodoPagoAltaDTO("  Ana Lopez  ", "5500-0000-0000-0004", "03/27", "1234"));
        // Then
        assertThat(resultado.titular()).isEqualTo("Ana Lopez");
        assertThat(resultado.ultimosDigitos()).isEqualTo("0004");
    }

    @Test
    void anadirSinDatosLanzaInvalido() {
        assertThatThrownBy(() -> servicio.anadir(ID_CLIENTE, null)).isInstanceOf(MetodoPagoInvalidoException.class);
        verifyNoInteractions(repositorio);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void anadirSinTitularLanzaInvalido(String titular) {
        assertThatThrownBy(() -> servicio.anadir(ID_CLIENTE, new MetodoPagoAltaDTO(titular, "4111111111114242", "12/28", "123")))
                .isInstanceOf(MetodoPagoInvalidoException.class)
                .hasMessage("Indica el titular de la tarjeta");
        verifyNoInteractions(repositorio);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"1234", "4111abcd11114242", "41111111111142421234"})
    void anadirConNumeroNoValidoLanzaInvalido(String numero) {
        assertThatThrownBy(() -> servicio.anadir(ID_CLIENTE, new MetodoPagoAltaDTO("Carlos", numero, "12/28", "123")))
                .isInstanceOf(MetodoPagoInvalidoException.class)
                .hasMessage("El numero de tarjeta debe tener entre 12 y 19 digitos");
        verifyNoInteractions(repositorio);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"13/28", "1/28", "12-28", "12/2028"})
    void anadirConCaducidadNoValidaLanzaInvalido(String caducidad) {
        assertThatThrownBy(() -> servicio.anadir(ID_CLIENTE, new MetodoPagoAltaDTO("Carlos", "4111111111114242", caducidad, "123")))
                .isInstanceOf(MetodoPagoInvalidoException.class)
                .hasMessage("La caducidad debe tener el formato MM/AA");
        verifyNoInteractions(repositorio);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"12", "12345", "abc"})
    void anadirConCvvNoValidoLanzaInvalido(String cvv) {
        assertThatThrownBy(() -> servicio.anadir(ID_CLIENTE, new MetodoPagoAltaDTO("Carlos", "4111111111114242", "12/28", cvv)))
                .isInstanceOf(MetodoPagoInvalidoException.class)
                .hasMessage("El CVV debe tener 3 o 4 digitos");
        verifyNoInteractions(repositorio);
    }

    @Test
    void anadirConElMaximoAlcanzadoLanzaInvalidoYNoGuarda() {
        // Given
        when(repositorio.contarPorCliente(new ObjectId(ID_CLIENTE)))
                .thenReturn((long) ServicioMetodoPago.MAXIMO_POR_CLIENTE);
        // When / Then
        assertThatThrownBy(() -> servicio.anadir(ID_CLIENTE, TARJETA_VALIDA))
                .isInstanceOf(MetodoPagoInvalidoException.class);
        verify(repositorio, never()).save(any());
    }

    // ---------- HU-24.3 Eliminar ----------

    @Test
    void eliminarUnMetodoPropioLoBorra() {
        // Given
        MetodoPago metodo = new MetodoPago(ID_CLIENTE, "Carlos", "4242", "12/28", Instant.now());
        when(repositorio.buscarDelCliente(new ObjectId(ID_METODO), new ObjectId(ID_CLIENTE)))
                .thenReturn(Optional.of(metodo));
        // When
        servicio.eliminar(ID_CLIENTE, ID_METODO);
        // Then
        verify(repositorio).delete(metodo);
    }

    @Test
    void eliminarUnMetodoAjenoOInexistenteLanzaNoEncontradoYNoBorraNada() {
        // Given: la consulta exige que sea de este cliente, asi que el de otro no aparece
        when(repositorio.buscarDelCliente(new ObjectId(ID_METODO), new ObjectId(ID_CLIENTE)))
                .thenReturn(Optional.empty());
        // When / Then
        assertThatThrownBy(() -> servicio.eliminar(ID_CLIENTE, ID_METODO))
                .isInstanceOf(MetodoPagoNoEncontradoException.class);
        verify(repositorio, never()).delete(any());
    }

    @Test
    void eliminarConIdMalFormadoLanzaNoEncontradoSinConsultar() {
        assertThatThrownBy(() -> servicio.eliminar(ID_CLIENTE, "no-es-un-id"))
                .isInstanceOf(MetodoPagoNoEncontradoException.class);
        verifyNoInteractions(repositorio);
    }
}