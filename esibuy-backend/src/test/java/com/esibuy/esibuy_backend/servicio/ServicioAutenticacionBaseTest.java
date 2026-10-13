package com.esibuy.esibuy_backend.servicio;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.esibuy.esibuy_backend.modelo.EstadoUsuario;
import com.esibuy.esibuy_backend.modelo.PerfilUsuario;
import com.esibuy.esibuy_backend.modelo.Rol;
import com.esibuy.esibuy_backend.modelo.Usuario;
import com.esibuy.esibuy_backend.repositorio.RepositorioUsuario;

/**
 * Fixture común de las pruebas de ServicioAutenticacion que pasan de la validación (camino feliz y rechazos).
 *
 * Deja los colaboradores mockeados sin comportamiento, salvo el hash ficticio que devuelve el codificador (de forma
 * tolerante: la implementación puede obtenerlo así o tenerlo fijo). Cada test configura solo lo que necesita.
 */
@ExtendWith(MockitoExtension.class)
abstract class ServicioAutenticacionBaseTest {

    protected static final String ID_USUARIO = "665f1c2e9b1e8a3d4c5b6a79";
    protected static final String EMAIL = "ana.garcia@ejemplo.es";
    protected static final String CONTRASENA = "Tren-Azul-Lluvia-77";
    protected static final String NOMBRE = "Ana";
    protected static final String HASH_USUARIO = "hash-del-usuario";
    protected static final String HASH_FICTICIO = "hash-ficticio";

    protected static final ContextoPeticion CONTEXTO = new ContextoPeticion("203.0.113.7", "JUnit");

    @Mock
    protected RepositorioUsuario repositorioUsuario;
    @Mock
    protected PasswordEncoder codificador;
    @Mock
    protected LimitadorIntentosLogin limitador;
    @Mock
    protected AuditoriaSeguridad auditoria;

    protected ServicioAutenticacion servicio;

    @BeforeEach
    void prepararServicio() {
        lenient().when(codificador.encode(anyString())).thenReturn(HASH_FICTICIO);
        servicio = new ServicioAutenticacion(repositorioUsuario, codificador, limitador, auditoria);
    }

    /** Usuario tal y como lo devolvería la BBDD: con id, un rol, el estado indicado y el hash de la contraseña. */
    protected static Usuario usuario(Rol rol, EstadoUsuario estado) {
        Usuario usuario = new Usuario(EMAIL, HASH_USUARIO, rol, PerfilUsuario.builder().nombre(NOMBRE).build());
        ReflectionTestUtils.setField(usuario, "id", ID_USUARIO);
        ReflectionTestUtils.setField(usuario, "estado", estado);
        return usuario;
    }

    /** El repositorio encuentra a este usuario por el correo normalizado. */
    protected void dadoUsuarioRegistrado(Usuario usuario) {
        when(repositorioUsuario.buscarPorEmail(EMAIL)).thenReturn(Optional.of(usuario));
    }

    /** El codificador da por buena la contraseña de prueba frente al hash del usuario, y por mala cualquier otra. */
    protected void dadaContrasenaCorrecta() {
        lenient().when(codificador.matches(CONTRASENA, HASH_USUARIO)).thenReturn(true);
    }
}
