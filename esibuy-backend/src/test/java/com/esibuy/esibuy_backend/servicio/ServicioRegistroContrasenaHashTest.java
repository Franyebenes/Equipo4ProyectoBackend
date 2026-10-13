package com.esibuy.esibuy_backend.servicio;

import com.esibuy.esibuy_backend.dto.SolicitudRegistroClienteDTO;
import com.esibuy.esibuy_backend.excepcion.DatosRegistroInvalidosException;
import com.esibuy.esibuy_backend.excepcion.RegistroNoCompletadoException;
import com.esibuy.esibuy_backend.modelo.Usuario;
import com.esibuy.esibuy_backend.seguridad.CodificadorContrasenaConPepper;
import com.esibuy.esibuy_backend.util.CapturadorLogs;
import com.esibuy.esibuy_backend.util.ConstructorSolicitudCliente;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Almacenamiento seguro de la contrasena. Aqui se usa el PasswordEncoder REAL (Argon2id con pepper)
 * con un coste bajo para que las pruebas sean rapidas. Los parametros de produccion se
 * comprueban aparte en CodificadorContrasenaTest (CP-REG-51).
 */
class ServicioRegistroContrasenaHashTest extends ServicioRegistroBaseTest {

    private static final String CONTRASENA = ConstructorSolicitudCliente.CONTRASENA_VALIDA;
    private static final String PEPPER_DE_PRUEBA = "pepper-de-prueba";

    // saltLength, hashLength, parallelism, memoria (KiB), iteraciones: minimos para ir rapido
    private final PasswordEncoder codificadorReal = new CodificadorContrasenaConPepper(
            PEPPER_DE_PRUEBA, new Argon2PasswordEncoder(16, 32, 1, 1 << 10, 1));

    @BeforeEach
    void usarCodificadorReal() {
        servicio = crearServicio(RELOJ_FIJO, codificadorReal);
    }

    @Test
    void registrarCliente_registroCorrecto_guardaUnHashArgon2idQueNoEsLaContrasena() { // CP-REG-09
        // Given
        SolicitudRegistroClienteDTO solicitud = ConstructorSolicitudCliente.unaSolicitudValida().construir();

        // When
        servicio.registrarCliente(solicitud);

        // Then
        String hash = usuarioGuardado().getPasswordHash();
        assertThat(hash)
                .startsWith("$argon2id$")
                .isNotEqualTo(CONTRASENA)
                .doesNotContain(CONTRASENA);
    }

    @Test
    void registrarCliente_dosUsuariosConLaMismaContrasena_generanHashesDistintos() { // CP-REG-10
        // Given
        SolicitudRegistroClienteDTO primero = ConstructorSolicitudCliente.unaSolicitudValida()
                .conEmail("primero@ejemplo.es").construir();
        SolicitudRegistroClienteDTO segundo = ConstructorSolicitudCliente.unaSolicitudValida()
                .conEmail("segundo@ejemplo.es").construir();

        // When
        servicio.registrarCliente(primero);
        servicio.registrarCliente(segundo);

        // Then
        verify(repositorioUsuario, times(2)).save(usuarioCaptor.capture());
        List<Usuario> guardados = usuarioCaptor.getAllValues();
        assertThat(guardados.get(0).getPasswordHash()).isNotEqualTo(guardados.get(1).getPasswordHash());
    }

    @Test
    void registrarCliente_hashGuardado_verificaConLaContrasenaOriginalYNoConOtra() { // CP-REG-11
        // Given
        SolicitudRegistroClienteDTO solicitud = ConstructorSolicitudCliente.unaSolicitudValida().construir();

        // When
        servicio.registrarCliente(solicitud);

        // Then
        String hash = usuarioGuardado().getPasswordHash();
        assertThat(codificadorReal.matches(CONTRASENA, hash)).isTrue();
        assertThat(codificadorReal.matches("Otra-Contrasena-99", hash)).isFalse();
    }

    @Test
    void toString_solicitudYEntidadYRespuesta_noExponenLaContrasenaNiElHash() { // CP-REG-12
        // Given
        SolicitudRegistroClienteDTO solicitud = ConstructorSolicitudCliente.unaSolicitudValida().construir();

        // When
        var respuesta = servicio.registrarCliente(solicitud);
        Usuario usuario = usuarioGuardado();

        // Then
        assertThat(solicitud.toString()).doesNotContain(CONTRASENA);
        assertThat(usuario.toString())
                .doesNotContain(CONTRASENA)
                .doesNotContain(usuario.getPasswordHash());
        assertThat(respuesta.toString()).doesNotContain(CONTRASENA);
    }

    @Test
    void registrarCliente_registroCorrecto_losLogsNoContienenContrasenaHashNiEmailCompleto() { // CP-REG-12
        // Given
        SolicitudRegistroClienteDTO solicitud = ConstructorSolicitudCliente.unaSolicitudValida().construir();

        // When
        List<String> textosDeLog;
        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            servicio.registrarCliente(solicitud);
            textosDeLog = logs.textosCompletos();
        }

        // Then
        String hash = usuarioGuardado().getPasswordHash();
        assertThat(textosDeLog).noneMatch(texto -> texto.contains(CONTRASENA));
        assertThat(textosDeLog).noneMatch(texto -> texto.contains(hash)|| texto.contains(ConstructorSolicitudCliente.EMAIL_POR_DEFECTO));
    }

    @Test
    void registrarCliente_registroDuplicado_losLogsYLaExcepcionNoContienenContrasenaNiEmailCompleto() { // CP-REG-12
        // Given: fallo por condicion de carrera, que es el camino que mas detalle suele registrar
        doThrow(new DuplicateKeyException("E11000 duplicate key error"))
                .when(repositorioUsuario).save(any());
        SolicitudRegistroClienteDTO solicitud = ConstructorSolicitudCliente.unaSolicitudValida().construir();

        // When
        List<String> textosDeLog;
        RegistroNoCompletadoException excepcion;
        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            excepcion = assertThrows(RegistroNoCompletadoException.class, () -> servicio.registrarCliente(solicitud));
            textosDeLog = logs.textosCompletos();
        }

        // Then
        assertThat(textosDeLog).noneMatch(texto -> texto.contains(CONTRASENA) || texto.contains(ConstructorSolicitudCliente.EMAIL_POR_DEFECTO));
        assertThat(excepcion.toString()).doesNotContain(CONTRASENA);
    }

    @Test
    void registrarCliente_validacionFallida_losLogsNoContienenLaContrasenaIntroducida() { // CP-REG-12
        // Given
        String contrasenaComun = "password1";
        when(diccionario.esComun(contrasenaComun)).thenReturn(true);
        SolicitudRegistroClienteDTO solicitud = ConstructorSolicitudCliente.unaSolicitudValida()
                .conContrasena(contrasenaComun).construir();

        // When
        List<String> textosDeLog;
        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            assertThrows(DatosRegistroInvalidosException.class, () -> servicio.registrarCliente(solicitud));
            textosDeLog = logs.textosCompletos();
        }

        // Then
        assertThat(textosDeLog).noneMatch(texto -> texto.contains(contrasenaComun));
    }
}