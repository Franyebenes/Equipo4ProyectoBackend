package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.esibuy.esibuy_backend.excepcion.ConflictoNegocioException;
import com.esibuy.esibuy_backend.excepcion.MetodoPagoInvalidoException;
import com.esibuy.esibuy_backend.excepcion.MetodoPagoNoEncontradoException;
import com.esibuy.esibuy_backend.modelo.Carrito;
import com.esibuy.esibuy_backend.modelo.MetodoPago;
import com.esibuy.esibuy_backend.modelo.PagoSuscripcion;
import com.esibuy.esibuy_backend.modelo.PerfilUsuario;
import com.esibuy.esibuy_backend.modelo.Producto;
import com.esibuy.esibuy_backend.modelo.Rol;
import com.esibuy.esibuy_backend.modelo.Usuario;
import com.esibuy.esibuy_backend.repositorio.RepositorioCarrito;
import com.esibuy.esibuy_backend.repositorio.RepositorioMetodoPago;
import com.esibuy.esibuy_backend.repositorio.RepositorioPagoSuscripcion;
import com.esibuy.esibuy_backend.repositorio.RepositorioProducto;
import com.esibuy.esibuy_backend.repositorio.RepositorioUsuario;

@ExtendWith(MockitoExtension.class)
class ServicioSuscripcionTest {

    private static final String ID = "665f1c2e9b1d4a0012345678";
    private static final String TARJETA = "665f1c2e9b1d4a00bbbbbbbb";
    private static final String NORMAL = "665f1c2e9b1d4a00cccccccc";
    private static final String SOLO_PREMIUM = "665f1c2e9b1d4a00dddddddd";
    private static final double PRECIO = 4.99;

    @Mock
    private RepositorioUsuario repositorioUsuario;
    @Mock
    private RepositorioMetodoPago repositorioMetodoPago;
    @Mock
    private RepositorioPagoSuscripcion repositorioPago;
    @Mock
    private RepositorioCarrito repositorioCarrito;
    @Mock
    private RepositorioProducto repositorioProducto;
    @Mock
    private ServicioCarrito servicioCarrito;
    private ServicioSuscripcion servicio;

    @BeforeEach
    void crearServicio() {
        Clock hoy = Clock.fixed(Instant.parse("2026-11-02T10:00:00Z"), ZoneId.of("Europe/Madrid"));
        servicio = new ServicioSuscripcion(repositorioUsuario, repositorioMetodoPago, repositorioPago,
                repositorioCarrito, repositorioProducto, servicioCarrito, hoy, PRECIO);
    }

    private void existeUsuarioCon(Rol rol) {
        PerfilUsuario perfil = PerfilUsuario.builder().nombre("Ana").apellidos("Lopez").build();
        when(repositorioUsuario.findById(ID))
                .thenReturn(Optional.of(new Usuario("ana@gmail.com", "$argon2id$hash", rol, perfil)));
    }

    private void tieneTarjeta(String caducidad) {
        when(repositorioMetodoPago.buscarDelCliente(new ObjectId(TARJETA), new ObjectId(ID)))
                .thenReturn(Optional.of(new MetodoPago(ID, "Ana Lopez", "4242", caducidad, Instant.EPOCH)));
    }

    @Test
    void pasarAPremium_tarjetaPropiaVigente_pasaAPremiumYGuardaJustificante() {
        existeUsuarioCon(Rol.CLIENTE);
        tieneTarjeta("11/26"); // caduca este mes: todavia vale
        when(repositorioUsuario.save(any(Usuario.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repositorioPago.save(any(PagoSuscripcion.class))).thenAnswer(inv -> inv.getArgument(0));

        ResultadoPremium resultado = servicio.pasarAPremium(ID, TARJETA);

        assertThat(resultado.usuario().getRoles()).containsExactly(Rol.PREMIUM);
        assertThat(resultado.pago().getUltimosDigitos()).isEqualTo("4242");
        assertThat(resultado.pago().getImporte()).isEqualTo(PRECIO);
        assertThat(resultado.pago().getConcepto()).isEqualTo(PagoSuscripcion.CONCEPTO_PREMIUM);
    }

    @Test
    void pasarAPremium_tarjetaCaducada_lanza400SinCambiarNada() {
        existeUsuarioCon(Rol.CLIENTE);
        tieneTarjeta("10/26");

        assertThatThrownBy(() -> servicio.pasarAPremium(ID, TARJETA)).isInstanceOf(MetodoPagoInvalidoException.class);
        verify(repositorioUsuario, never()).save(any());
        verifyNoInteractions(repositorioPago);
    }

    @Test
    void pasarAPremium_tarjetaDeOtroCliente_lanza404SinCambiarNada() {
        existeUsuarioCon(Rol.CLIENTE);
        when(repositorioMetodoPago.buscarDelCliente(new ObjectId(TARJETA), new ObjectId(ID)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.pasarAPremium(ID, TARJETA))
                .isInstanceOf(MetodoPagoNoEncontradoException.class);
        verify(repositorioUsuario, never()).save(any());
        verifyNoInteractions(repositorioPago);
    }

    @Test
    void pasarAPremium_idDeTarjetaMalFormado_lanza404SinConsultar() {
        existeUsuarioCon(Rol.CLIENTE);

        assertThatThrownBy(() -> servicio.pasarAPremium(ID, "no-es-un-id"))
                .isInstanceOf(MetodoPagoNoEncontradoException.class);
        verifyNoInteractions(repositorioMetodoPago, repositorioPago);
    }

    @Test
    void pasarAPremium_yaEsPremium_lanzaConflictoSinCobrar() {
        existeUsuarioCon(Rol.PREMIUM);

        assertThatThrownBy(() -> servicio.pasarAPremium(ID, TARJETA))
                .isInstanceOf(ConflictoNegocioException.class)
                .extracting("codigo").isEqualTo(ServicioSuscripcion.CODIGO_YA_ES_PREMIUM);
        verifyNoInteractions(repositorioMetodoPago, repositorioPago);
    }

    @Test
    void volverAEstandar_conProductoSoloPremiumEnElCarrito_loQuitaYDevuelveElStock() {
        existeUsuarioCon(Rol.PREMIUM);
        when(repositorioUsuario.save(any(Usuario.class))).thenAnswer(inv -> inv.getArgument(0));
        Carrito carrito = new Carrito(ID);
        carrito.anadir(NORMAL, 1);
        carrito.anadir(SOLO_PREMIUM, 2);
        when(repositorioCarrito.buscarPorCliente(new ObjectId(ID))).thenReturn(Optional.of(carrito));
        when(repositorioProducto.findAllById(List.of(NORMAL, SOLO_PREMIUM))).thenReturn(List.of(
                Producto.builder().id(NORMAL).soloPremium(false).build(),
                Producto.builder().id(SOLO_PREMIUM).soloPremium(true).build()));

        ResultadoBajaPremium resultado = servicio.volverAEstandar(ID);

        assertThat(resultado.usuario().getRoles()).containsExactly(Rol.CLIENTE);
        assertThat(resultado.productosQuitados()).isEqualTo(1);
        verify(servicioCarrito).eliminar(ID, SOLO_PREMIUM);
        verify(servicioCarrito, never()).eliminar(ID, NORMAL);
    }

    @Test
    void volverAEstandar_sinCarrito_soloCambiaElRol() {
        existeUsuarioCon(Rol.PREMIUM);
        when(repositorioUsuario.save(any(Usuario.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repositorioCarrito.buscarPorCliente(new ObjectId(ID))).thenReturn(Optional.empty());

        assertThat(servicio.volverAEstandar(ID).productosQuitados()).isZero();
        verifyNoInteractions(repositorioProducto, servicioCarrito);
    }

    @Test
    void volverAEstandar_clienteEstandar_lanzaConflicto() {
        existeUsuarioCon(Rol.CLIENTE);

        assertThatThrownBy(() -> servicio.volverAEstandar(ID))
                .isInstanceOf(ConflictoNegocioException.class)
                .extracting("codigo").isEqualTo(ServicioSuscripcion.CODIGO_NO_ES_PREMIUM);
    }
}