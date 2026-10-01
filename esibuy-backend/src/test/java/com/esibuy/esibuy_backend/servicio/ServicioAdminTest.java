/*package com.esibuy.esibuy_backend.servicio;

import com.esibuy.esibuy_backend.dto.RegistroAdminDTO;
import com.esibuy.esibuy_backend.excepcion.ExcepcionAccesoDenegado;
import com.esibuy.esibuy_backend.excepcion.ExcepcionEmailDuplicado;
import com.esibuy.esibuy_backend.modelo.Usuario;
import com.esibuy.esibuy_backend.repositorio.UsuarioRepositorio;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ServicioAdminTest {

    @Mock
    private UsuarioRepositorio usuarioRepositorio;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private ServicioUsuario servicioUsuario;

    private RegistroAdminDTO dtoValido;
    private Usuario adminAutenticado;

    @BeforeEach
    void setUp() {
        dtoValido = new RegistroAdminDTO();
        dtoValido.setNombre("Marta");
        dtoValido.setApellidos("Lopez");
        dtoValido.setEmail("marta@esibuy.com");
        dtoValido.setSede("Madrid");
        dtoValido.setContrasena("Admin123!");
        dtoValido.setRepetirContrasena("Admin123!");

        adminAutenticado = new Usuario();
        adminAutenticado.setRoles(java.util.List.of("ADMIN"));
    }

    @Test
    @DisplayName("Alta de admin exitosa asigna rol ADMIN, sede y fecha de incorporación automática")
    void altaAdmin_DatosValidos_AsignaRolAdminYFechaAutomatica() {
        // Given
        when(usuarioRepositorio.findByEmail(dtoValido.getEmail())).thenReturn(Optional.empty());
        when(passwordEncoder.encode(dtoValido.getContrasena())).thenReturn("hash_admin");
        LocalDate hoy = LocalDate.now();

        // When
        servicioUsuario.altaAdministrador(dtoValido, adminAutenticado);

        // Then
        ArgumentCaptor<Usuario> captor = ArgumentCaptor.forClass(Usuario.class);
        verify(usuarioRepositorio).save(captor.capture());
        Usuario guardado = captor.getValue();
        assertTrue(guardado.getRoles().contains("ADMIN"));
        assertEquals("Madrid", guardado.getPerfil().getSede());
        assertEquals(hoy, guardado.getPerfil().getFechaIncorporacion()); // Asignada automáticamente
        assertEquals("ACTIVE", guardado.getStatus()); // Activado por defecto
    }

    @Test
    @DisplayName("Usuario sin rol ADMIN no puede dar de alta administradores")
    void altaAdmin_UsuarioSinRolAdmin_LanzaExcepcionAccesoDenegado() {
        // Given
        Usuario noAdmin = new Usuario();
        noAdmin.setRoles(java.util.List.of("CUSTOMER"));

        // When & Then
        assertThrows(ExcepcionAccesoDenegado.class,
                () -> servicioUsuario.altaAdministrador(dtoValido, noAdmin));
        verify(usuarioRepositorio, never()).save(any());
    }

    @Test
    @DisplayName("Email duplicado en alta de admin debe lanzar ExcepcionEmailDuplicado")
    void altaAdmin_EmailDuplicado_LanzaExcepcion() {
        // Given
        when(usuarioRepositorio.findByEmail(dtoValido.getEmail())).thenReturn(Optional.of(new Usuario()));

        // When & Then
        assertThrows(ExcepcionEmailDuplicado.class,
                () -> servicioUsuario.altaAdministrador(dtoValido, adminAutenticado));
    }
}*/