package com.esibuy.esibuy_backend.servicio;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.esibuy.esibuy_backend.dto.SolicitudAltaAdministradorDTO;
import com.esibuy.esibuy_backend.modelo.EstadoUsuario;
import com.esibuy.esibuy_backend.modelo.PerfilUsuario;
import com.esibuy.esibuy_backend.modelo.Rol;
import com.esibuy.esibuy_backend.modelo.Usuario;
import com.esibuy.esibuy_backend.repositorio.RepositorioProducto;
import com.esibuy.esibuy_backend.repositorio.RepositorioUsuario;

/**
 * Base comun de las pruebas del servicio de gestion de usuarios (administracion).
 * Los repositorios y validadores son mocks: las pruebas no tocan la base de datos real.
 */
@ExtendWith(MockitoExtension.class)
abstract class ServicioGestionUsuariosBase {

    protected static final String ID_USUARIO = new ObjectId().toHexString();
    protected static final String ID_ADMIN = new ObjectId().toHexString();
    protected static final String EMAIL_USUARIO = "carlos@esibuy.com";
    protected static final String EMAIL_NUEVO_ADMIN = "laura.admin@esibuy.com";
    protected static final String CONTRASENA = "Contrasena.Segura2026";
    // 9 de octubre de 2026 a mediodia en Madrid
    protected static final Clock RELOJ = Clock.fixed(Instant.parse("2026-10-09T10:00:00Z"), ZoneOffset.UTC);

    @Mock protected RepositorioUsuario repositorioUsuario;
    @Mock protected RepositorioProducto repositorioProducto;
    @Mock protected ValidadorDominioEmail validadorDominioEmail;
    @Mock protected ValidadorContrasena validadorContrasena;
    @Mock protected PasswordEncoder codificadorContrasena;
    @Mock protected CatalogoAvatares catalogoAvatares;
    @Mock protected MongoTemplate mongoTemplate;

    protected ServicioGestionUsuarios servicio;

    @BeforeEach
    void crearServicio() {
        servicio = new ServicioGestionUsuarios(repositorioUsuario, repositorioProducto, validadorDominioEmail,
                validadorContrasena, codificadorContrasena, catalogoAvatares, mongoTemplate, RELOJ);
    }

    protected Usuario usuario(String id, Rol rol, EstadoUsuario estado) {
        PerfilUsuario perfil = PerfilUsuario.builder().nombre("Carlos").apellidos("Lopez").build();
        Usuario usuario = new Usuario(EMAIL_USUARIO, "hash", rol, perfil);
        ReflectionTestUtils.setField(usuario, "id", id);
        usuario.cambiarEstado(estado);
        return usuario;
    }

    protected void existeEnBaseDeDatos(Usuario usuario) {
        when(repositorioUsuario.findByIdAndEstadoNot(usuario.getId(), EstadoUsuario.ELIMINADO))
                .thenReturn(Optional.of(usuario));
    }

    protected void noExisteEnBaseDeDatos(String id) {
        when(repositorioUsuario.findByIdAndEstadoNot(id, EstadoUsuario.ELIMINADO)).thenReturn(Optional.empty());
    }

    protected void guardarDevuelveLoMismo() {
        when(repositorioUsuario.save(any(Usuario.class))).thenAnswer(invocacion -> invocacion.getArgument(0));
    }

    protected SolicitudAltaAdministradorDTO solicitudAlta(String contrasena, String repetirContrasena) {
        SolicitudAltaAdministradorDTO solicitud = mock(SolicitudAltaAdministradorDTO.class);
        when(solicitud.nombre()).thenReturn("Laura");
        when(solicitud.apellidos()).thenReturn("Martin");
        when(solicitud.email()).thenReturn(EMAIL_NUEVO_ADMIN);
        when(solicitud.sede()).thenReturn("Ciudad Real");
        when(solicitud.avatar()).thenReturn(null);
        when(solicitud.contrasena()).thenReturn(contrasena);
        when(solicitud.repetirContrasena()).thenReturn(repetirContrasena);
        return solicitud;
    }
}