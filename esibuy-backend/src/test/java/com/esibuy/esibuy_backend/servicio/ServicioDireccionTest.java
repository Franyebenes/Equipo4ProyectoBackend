package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.esibuy.esibuy_backend.dto.SolicitudDireccionDTO;
import com.esibuy.esibuy_backend.excepcion.ConflictoNegocioException;
import com.esibuy.esibuy_backend.excepcion.RecursoNoEncontradoException;
import com.esibuy.esibuy_backend.modelo.Direccion;
import com.esibuy.esibuy_backend.modelo.DireccionEnvio;
import com.esibuy.esibuy_backend.repositorio.RepositorioDireccion;

@ExtendWith(MockitoExtension.class)
class ServicioDireccionTest {

    private static final String USUARIO = "665f1c2e9b1d4a0012345678";
    private static final String DIRECCION = "665f1c2e9b1d4a0087654321";
    private static final String NUEVA = "665f1c2e9b1d4a00aaaaaaaa";

    @Mock
    private RepositorioDireccion repositorio;
    @Mock
    private ComprobadorUsoDireccion comprobadorUso;
    @InjectMocks
    private ServicioDireccion servicio;

    private static SolicitudDireccionDTO solicitud(boolean predeterminada) {
        return new SolicitudDireccionDTO("Ana Lopez", "Calle Mayor", "5", "", "13001", "Ciudad Real",
                "Ciudad Real", predeterminada);
    }

    private static Direccion direccion(String id, boolean predeterminada, String pisoPuerta) {
        Direccion d = new Direccion(USUARIO);
        d.actualizarDatos("Ana Lopez", "Calle Mayor", "5", pisoPuerta, "13001", "Ciudad Real", "Ciudad Real");
        d.marcarPredeterminada(predeterminada);
        ReflectionTestUtils.setField(d, "id", id);
        return d;
    }

    private static Direccion direccion(String id, boolean predeterminada) {
        return direccion(id, predeterminada, null);
    }

    /** Simula que MongoDB asigna un id al guardar. */
    private void guardarAsignaId() {
        when(repositorio.save(any(Direccion.class))).thenAnswer(inv -> {
            Direccion d = inv.getArgument(0);
            if (d.getId() == null) {
                ReflectionTestUtils.setField(d, "id", NUEVA);
            }
            return d;
        });
    }

    private void existe(Direccion direccion) {
        when(repositorio.buscarPorIdYUsuario(new ObjectId(DIRECCION), new ObjectId(USUARIO)))
                .thenReturn(Optional.of(direccion));
    }

    @Test
    void crear_primeraDireccion_quedaPredeterminadaAunqueNoSePida() {
        when(repositorio.contarPorUsuario(new ObjectId(USUARIO))).thenReturn(0L);
        guardarAsignaId();

        assertThat(servicio.crear(USUARIO, solicitud(false)).predeterminada()).isTrue();
    }

    @Test
    void crear_nuevaPredeterminada_quitaLaMarcaALaAnterior() {
        Direccion anterior = direccion(DIRECCION, true);
        when(repositorio.contarPorUsuario(new ObjectId(USUARIO))).thenReturn(1L);
        guardarAsignaId();
        when(repositorio.buscarPorUsuario(new ObjectId(USUARIO))).thenReturn(List.of(anterior));

        servicio.crear(USUARIO, solicitud(true));

        assertThat(anterior.isPredeterminada()).isFalse();
        verify(repositorio).saveAll(List.of(anterior));
    }

    @Test
    void crear_conLimiteAlcanzado_lanzaConflictoYNoGuarda() {
        when(repositorio.contarPorUsuario(new ObjectId(USUARIO))).thenReturn(10L);

        assertThatThrownBy(() -> servicio.crear(USUARIO, solicitud(false)))
                .isInstanceOf(ConflictoNegocioException.class)
                .extracting("codigo").isEqualTo(ServicioDireccion.CODIGO_LIMITE);
        verify(repositorio, never()).save(any());
    }

    @Test
    void modificar_idMalFormado_lanzaNoEncontradaSinConsultar() {
        assertThatThrownBy(() -> servicio.modificar(USUARIO, "no-es-un-id", solicitud(false)))
                .isInstanceOf(RecursoNoEncontradoException.class);
        verifyNoInteractions(repositorio, comprobadorUso);
    }

    @Test
    void modificar_direccionDeOtroUsuario_lanzaNoEncontrada() {
        when(repositorio.buscarPorIdYUsuario(new ObjectId(DIRECCION), new ObjectId(USUARIO)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.modificar(USUARIO, DIRECCION, solicitud(false)))
                .isInstanceOf(RecursoNoEncontradoException.class);
    }

    @Test
    void modificar_usadaEnPedidoEnCurso_lanzaConflictoYNoGuarda() {
        existe(direccion(DIRECCION, true));
        when(comprobadorUso.estaEnPedidoEnCurso(USUARIO, DIRECCION)).thenReturn(true);

        assertThatThrownBy(() -> servicio.modificar(USUARIO, DIRECCION, solicitud(false)))
                .isInstanceOf(ConflictoNegocioException.class)
                .extracting("codigo").isEqualTo(ServicioDireccion.CODIGO_EN_USO);
        verify(repositorio, never()).save(any());
    }

    @Test
    void modificar_sinPedidosEnCurso_guardaLosDatosNuevos() {
        existe(direccion(DIRECCION, true));
        when(comprobadorUso.estaEnPedidoEnCurso(USUARIO, DIRECCION)).thenReturn(false);
        guardarAsignaId();

        assertThat(servicio.modificar(USUARIO, DIRECCION, solicitud(false)).pisoPuerta()).isNull(); // "" pasa a null
    }

    @Test
    void marcarPredeterminada_usadaEnPedido_seMarcaIgualmente() {
        existe(direccion(DIRECCION, false));
        guardarAsignaId();
        when(repositorio.buscarPorUsuario(new ObjectId(USUARIO))).thenReturn(List.of());

        assertThat(servicio.marcarPredeterminada(USUARIO, DIRECCION).predeterminada()).isTrue();
        verifyNoInteractions(comprobadorUso);
    }

    @Test
    void eliminar_usadaEnPedidoEnCurso_lanzaConflictoYNoBorra() {
        existe(direccion(DIRECCION, false));
        when(comprobadorUso.estaEnPedidoEnCurso(USUARIO, DIRECCION)).thenReturn(true);

        assertThatThrownBy(() -> servicio.eliminar(USUARIO, DIRECCION)).isInstanceOf(ConflictoNegocioException.class);
        verify(repositorio, never()).delete(any());
    }

    @Test
    void eliminar_predeterminada_pasaLaMarcaALaSiguiente() {
        Direccion borrada = direccion(DIRECCION, true);
        Direccion siguiente = direccion(NUEVA, false);
        existe(borrada);
        when(comprobadorUso.estaEnPedidoEnCurso(USUARIO, DIRECCION)).thenReturn(false);
        when(repositorio.buscarPorUsuario(new ObjectId(USUARIO))).thenReturn(List.of(siguiente));

        servicio.eliminar(USUARIO, DIRECCION);

        verify(repositorio).delete(borrada);
        assertThat(siguiente.isPredeterminada()).isTrue();
        verify(repositorio).save(siguiente);
    }

    @Test
    void paraPedido_direccionPropia_devuelveCopiaConLineaDeCalleYSuId() {
        existe(direccion(DIRECCION, true, "2B"));

        DireccionEnvio copia = servicio.paraPedido(USUARIO, DIRECCION);

        assertThat(copia.getCalle()).isEqualTo("Calle Mayor 5, 2B");
        assertThat(copia.getCiudad()).isEqualTo("Ciudad Real");
        assertThat(copia.getCodigoPostal()).isEqualTo("13001");
        assertThat(copia.getIdDireccion()).isEqualTo(DIRECCION);
    }

    @Test
    void paraPedido_direccionDeOtroCliente_lanzaNoEncontrada() {
        when(repositorio.buscarPorIdYUsuario(new ObjectId(DIRECCION), new ObjectId(USUARIO)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.paraPedido(USUARIO, DIRECCION))
                .isInstanceOf(RecursoNoEncontradoException.class);
    }
}