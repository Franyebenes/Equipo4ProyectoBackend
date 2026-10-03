package com.esibuy.esibuy_backend.servicio;

import com.esibuy.esibuy_backend.modelo.Usuario;
import com.esibuy.esibuy_backend.repositorio.RepositorioCategoria;
import com.esibuy.esibuy_backend.repositorio.RepositorioUsuario;
import com.esibuy.esibuy_backend.util.ConstructorSolicitudVendedor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Fixture comun de las pruebas unitarias de ServicioRegistro.
 *
 * Deja todos los colaboradores mockeados con un comportamiento "camino feliz" (de forma tolerante,
 * para que cada test sobrescriba solo lo que necesita). El ValidadorContrasena es el real.
 */
@ExtendWith(MockitoExtension.class)
abstract class ServicioRegistroBaseTest {

    protected static final ZoneId ZONA_APLICACION = ZoneId.of("Europe/Madrid");
    /** 1 de octubre de 2026 a mediodia. */
    protected static final Clock RELOJ_FIJO = Clock.fixed(Instant.parse("2026-10-01T10:00:00Z"), ZONA_APLICACION);

    protected static final String AVATAR_VALIDO = "avatar-01";
    protected static final String OTRO_AVATAR_VALIDO = "avatar-03";
    protected static final String AVATAR_CLIENTE_POR_DEFECTO = "avatar-defecto-cliente";
    protected static final String AVATAR_VENDEDOR_POR_DEFECTO = "avatar-defecto-vendedor";
    protected static final String HASH_SIMULADO = "hash-simulado";

    @Mock
    protected RepositorioUsuario repositorioUsuario;
    @Mock
    protected RepositorioCategoria repositorioCategoria;
    @Mock
    protected ValidadorDominioEmail validadorDominioEmail;
    @Mock
    protected DiccionarioContrasenasProhibidas diccionario;
    @Mock
    protected CatalogoAvatares catalogoAvatares;
    @Mock
    protected PasswordEncoder codificador;

    @Captor
    protected ArgumentCaptor<Usuario> usuarioCaptor;

    protected ServicioRegistro servicio;

    @BeforeEach
    void prepararColaboradoresYServicio() {
        lenient().when(repositorioUsuario.existePorEmail(anyString())).thenReturn(false);
        lenient().when(repositorioUsuario.existePorNombreComercial(anyString())).thenReturn(false);
        lenient().when(repositorioUsuario.save(any(Usuario.class)))
                .thenAnswer(invocacion -> invocacion.getArgument(0));

        lenient().when(repositorioCategoria.existsById(ConstructorSolicitudVendedor.CATEGORIA_VALIDA))
                .thenReturn(true);

        lenient().when(validadorDominioEmail.tieneDominioValido(anyString())).thenReturn(true);

        lenient().when(diccionario.esComun(anyString())).thenReturn(false);
        lenient().when(diccionario.estaFiltrada(anyString())).thenReturn(false);

        lenient().when(catalogoAvatares.esAvatarValido(AVATAR_VALIDO)).thenReturn(true);
        lenient().when(catalogoAvatares.esAvatarValido(OTRO_AVATAR_VALIDO)).thenReturn(true);
        lenient().when(catalogoAvatares.avatarPorDefectoCliente()).thenReturn(AVATAR_CLIENTE_POR_DEFECTO);
        lenient().when(catalogoAvatares.avatarPorDefectoVendedor()).thenReturn(AVATAR_VENDEDOR_POR_DEFECTO);

        lenient().when(codificador.encode(any())).thenReturn(HASH_SIMULADO);

        servicio = crearServicio(RELOJ_FIJO);
    }

    /** Crea el servicio con un reloj concreto y el codificador mockeado. */
    protected ServicioRegistro crearServicio(Clock reloj) {
        return crearServicio(reloj, codificador);
    }

    /** Crea el servicio con un reloj y un codificador concretos (p. ej. el real en las pruebas de hash). */
    protected ServicioRegistro crearServicio(Clock reloj, PasswordEncoder codificadorAUsar) {
        return new ServicioRegistro(
                repositorioUsuario,
                repositorioCategoria,
                validadorDominioEmail,
                new ValidadorContrasena(diccionario),
                codificadorAUsar,
                catalogoAvatares,
                reloj);
    }

    /** Verifica que se guardo exactamente un usuario y lo devuelve. */
    protected Usuario usuarioGuardado() {
        verify(repositorioUsuario).save(usuarioCaptor.capture());
        return usuarioCaptor.getValue();
    }

    protected void verificarQueNoSeGuardoNingunUsuario() {
        verify(repositorioUsuario, never()).save(any());
    }
}