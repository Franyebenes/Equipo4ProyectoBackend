/*package com.esibuy.esibuy_backend.servicio;

import com.esibuy.esibuy_backend.dto.RegistroClienteDTO;
import com.esibuy.esibuy_backend.excepcion.*;
import com.esibuy.esibuy_backend.modelo.TipoUsuario;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ServicioUsuarioTest {

    @Mock
    private UsuarioRepositorio usuarioRepositorio;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private ServicioUsuario servicioUsuario;

    private RegistroClienteDTO dtoValido;

    @BeforeEach
    void setUp() {
        // Given (Arrange) - Configuración inicial para todos los tests
        dtoValido = new RegistroClienteDTO();
        dtoValido.setNombre("Juan");
        dtoValido.setApellidos("Perez");
        dtoValido.setFechaNacimiento(LocalDate.of(1990, 1, 1)); // Mayor de edad
        dtoValido.setDni("12345678A");
        dtoValido.setEmail("juan.perez@test.com");
        dtoValido.setTelefono("612345678"); // 9 dígitos
        dtoValido.setAvatar("avatar_1.png");
        dtoValido.setContrasena("P@ssw0rd123");
        dtoValido.setRepetirContrasena("P@ssw0rd123");
        dtoValido.setTipoUsuario(TipoUsuario.NORMAL);
    }

    // ==========================================
    // HAPPY PATHS
    // ==========================================

    @Test
    @DisplayName("Registro exitoso con datos válidos debe guardar el usuario y hashear la contraseña")
    void registrarCliente_DatosValidos_GuardaUsuarioYHasheaContrasena() {
        // Given
        when(usuarioRepositorio.findByEmail(dtoValido.getEmail())).thenReturn(Optional.empty());
        when(passwordEncoder.encode(dtoValido.getContrasena())).thenReturn("hash_seguro_123");
        
        // When
        servicioUsuario.registrarCliente(dtoValido);

        // Then
        ArgumentCaptor<Usuario> usuarioCaptor = ArgumentCaptor.forClass(Usuario.class);
        verify(usuarioRepositorio, times(1)).save(usuarioCaptor.capture());
        
        Usuario usuarioGuardado = usuarioCaptor.getValue();
        assertEquals("juan.perez@test.com", usuarioGuardado.getEmail());
        assertEquals("hash_seguro_123", usuarioGuardado.getPasswordHash()); // Verifica que NO se guarda en texto plano
        assertTrue(usuarioGuardado.getRoles().contains("CUSTOMER")); // Rol por defecto
        assertNotNull(usuarioGuardado.getPerfil());
        assertEquals("Juan", usuarioGuardado.getPerfil().getNombre());
    }

    @Test
    @DisplayName("Si no se proporciona avatar, se asigna uno por defecto")
    void registrarCliente_SinAvatar_AsignaAvatarPorDefecto() {
        // Given
        dtoValido.setAvatar(null);
        when(usuarioRepositorio.findByEmail(anyString())).thenReturn(Optional.empty());
        when(passwordEncoder.encode(anyString())).thenReturn("hash");

        // When
        servicioUsuario.registrarCliente(dtoValido);

        // Then
        ArgumentCaptor<Usuario> usuarioCaptor = ArgumentCaptor.forClass(Usuario.class);
        verify(usuarioRepositorio).save(usuarioCaptor.capture());
        assertEquals("avatar_por_defecto.png", usuarioCaptor.getValue().getPerfil().getAvatarUrl());
    }

    // ==========================================
    // VALIDACIONES DE ERRORES Y EDGE CASES
    // ==========================================

    @Test
    @DisplayName("Email ya registrado debe lanzar ExcepcionEmailDuplicado")
    void registrarCliente_EmailYaExiste_LanzaExcepcionEmailDuplicado() {
        // Given
        when(usuarioRepositorio.findByEmail(dtoValido.getEmail())).thenReturn(Optional.of(new Usuario()));

        // When & Then
        assertThrows(ExcepcionEmailDuplicado.class, () -> servicioUsuario.registrarCliente(dtoValido));
        verify(usuarioRepositorio, never()).save(any(Usuario.class));
    }

    @Test
    @DisplayName("Contraseñas no coincidentes debe lanzar ExcepcionContrasenasNoCoinciden")
    void registrarCliente_ContrasenasNoCoinciden_LanzaExcepcion() {
        // Given
        dtoValido.setRepetirContrasena("OtraContrasena123!");

        // When & Then
        assertThrows(ExcepcionContrasenasNoCoinciden.class, () -> servicioUsuario.registrarCliente(dtoValido));
    }

    @Test
    @DisplayName("Contraseña débil debe lanzar ExcepcionContrasenaDebil")
    void registrarCliente_ContrasenaDebil_LanzaExcepcion() {
        // Given
        dtoValido.setContrasena("123");
        dtoValido.setRepetirContrasena("123");

        // When & Then
        assertThrows(ExcepcionContrasenaDebil.class, () -> servicioUsuario.registrarCliente(dtoValido));
    }

    @Test
    @DisplayName("Usuario menor de edad debe lanzar ExcepcionMenorEdad")
    void registrarCliente_MenorDeEdad_LanzaExcepcion() {
        // Given
        dtoValido.setFechaNacimiento(LocalDate.now().minusYears(17)); // 17 años

        // When & Then
        assertThrows(ExcepcionMenorEdad.class, () -> servicioUsuario.registrarCliente(dtoValido));
    }

    @Test
    @DisplayName("Teléfono inválido (no 9 dígitos) debe lanzar ExcepcionTelefonoInvalido")
    void registrarCliente_TelefonoInvalido_LanzaExcepcion() {
        // Given
        dtoValido.setTelefono("12345"); // Menos de 9 dígitos

        // When & Then
        assertThrows(ExcepcionTelefonoInvalido.class, () -> servicioUsuario.registrarCliente(dtoValido));
    }

    @Test
    @DisplayName("Campos obligatorios vacíos deben lanzar ExcepcionCamposObligatorios")
    void registrarCliente_CamposObligatoriosVacios_LanzaExcepcion() {
        // Given
        dtoValido.setNombre(null);

        // When & Then
        assertThrows(ExcepcionCamposObligatorios.class, () -> servicioUsuario.registrarCliente(dtoValido));
    }

    // ==========================================
    // PRUEBAS DE SEGURIDAD (RBAC)
    // ==========================================

    @Test
    @DisplayName("Intento de asignar rol ADMIN debe ser ignorado y asignar CUSTOMER por defecto")
    void registrarCliente_IntentoAsignarRolAdmin_AsignaRolClientePorDefecto() {
        // Given
        dtoValido.setTipoUsuario(TipoUsuario.ADMIN); // Intento de escalada de privilegios
        when(usuarioRepositorio.findByEmail(anyString())).thenReturn(Optional.empty());
        when(passwordEncoder.encode(anyString())).thenReturn("hash");

        // When
        servicioUsuario.registrarCliente(dtoValido);

        // Then
        ArgumentCaptor<Usuario> usuarioCaptor = ArgumentCaptor.forClass(Usuario.class);
        verify(usuarioRepositorio).save(usuarioCaptor.capture());
        
        Usuario usuarioGuardado = usuarioCaptor.getValue();
        assertFalse(usuarioGuardado.getRoles().contains("ADMIN"));
        assertTrue(usuarioGuardado.getRoles().contains("CUSTOMER"));
    }
}*/