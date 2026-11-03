package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.text.Normalizer;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InOrder;

import com.esibuy.esibuy_backend.dto.SolicitudLoginDTO;
import com.esibuy.esibuy_backend.modelo.EstadoUsuario;
import com.esibuy.esibuy_backend.modelo.Rol;

/**
 * Camino feliz de la autenticación.
 *
 * Orden TDD (plan de pruebas, sección 8): paso 3.
 * Casos: CP-LOG-01, CP-LOG-05, CP-LOG-06, CP-LOG-07.
 * Colaboradores: RepositorioUsuario, PasswordEncoder, LimitadorIntentosLogin y AuditoriaSeguridad
 * mockeados; Clock fijo (ver ServicioAutenticacionBaseTest).
 *
 * Desarrollada con el ciclo Red-Green-Refactor: primero el test (Arrange, Act, Assert), después el código
 * mínimo que lo hace pasar. Terminada: ya no lleva la etiqueta pendiente-login y entra en la regresión.
 */
class ServicioAutenticacionCaminoFelizTest extends ServicioAutenticacionBaseTest {

    // ------------------------------------------------------------------ CP-LOG-01

    @ParameterizedTest(name = "usuario {0}")
    @EnumSource(Rol.class)
    void autenticar_credencialesCorrectasDeCadaTipoDeUsuario_devuelveResultadoConRolUnico( // CP-LOG-01
            Rol rol) {
        // Given: cliente, premium, vendedor o administrador, con la cuenta activa y la contraseña correcta
        dadoUsuarioRegistrado(usuario(rol, EstadoUsuario.ACTIVO));
        dadaContrasenaCorrecta();

        // When
        ResultadoAutenticacion resultado = servicio.autenticar(new SolicitudLoginDTO(EMAIL, CONTRASENA), CONTEXTO);

        // Then: id, email, nombre y un único rol (premium nunca como cliente más premium)
        assertThat(resultado).isEqualTo(new ResultadoAutenticacion(ID_USUARIO, EMAIL, NOMBRE, rol));
    }

    // ------------------------------------------------------------------ CP-LOG-05

    @Test
    void autenticar_correoConMayusculasYEspacios_loNormalizaAntesDeBuscarEnLaBbdd() { // CP-LOG-05
        // Given: el usuario está guardado con el correo normalizado
        dadoUsuarioRegistrado(usuario(Rol.CLIENTE, EstadoUsuario.ACTIVO));
        dadaContrasenaCorrecta();

        // When: el correo llega con espacios y mayúsculas
        ResultadoAutenticacion resultado = servicio.autenticar(
                new SolicitudLoginDTO("  ANA.Garcia@Ejemplo.ES ", CONTRASENA), CONTEXTO);

        // Then: se consulta con el correo recortado y en minúsculas, y se autentica con normalidad
        verify(repositorioUsuario).buscarPorEmail(EMAIL);
        assertThat(resultado.email()).isEqualTo(EMAIL);
    }

    // ------------------------------------------------------------------ CP-LOG-06

    private static final String CONTRASENA_CON_ACENTOS_NFC =
            Normalizer.normalize("Contraseña-Ñandú-77", Normalizer.Form.NFC);
    private static final String CONTRASENA_CON_ACENTOS_NFD =
            Normalizer.normalize(CONTRASENA_CON_ACENTOS_NFC, Normalizer.Form.NFD);

    static Stream<Arguments> contrasenasIntroducidasEnCadaForma() {
        return Stream.of(
                arguments("NFC", CONTRASENA_CON_ACENTOS_NFC),
                arguments("NFD", CONTRASENA_CON_ACENTOS_NFD));
    }

    @ParameterizedTest(name = "contraseña introducida en {0}")
    @MethodSource("contrasenasIntroducidasEnCadaForma")
    void autenticar_contrasenaEnNfcOEnNfd_iniciaSesionConLaMismaNormalizacionQueElRegistro( // CP-LOG-06
            String forma, String contrasenaIntroducida) {
        // Given: el registro guardó el hash de la contraseña normalizada a NFC
        assertThat(CONTRASENA_CON_ACENTOS_NFD).isNotEqualTo(CONTRASENA_CON_ACENTOS_NFC);
        dadoUsuarioRegistrado(usuario(Rol.CLIENTE, EstadoUsuario.ACTIVO));
        when(codificador.matches(CONTRASENA_CON_ACENTOS_NFC, HASH_USUARIO)).thenReturn(true);

        // When: se introduce en NFC o en NFD
        ResultadoAutenticacion resultado = servicio.autenticar(
                new SolicitudLoginDTO(EMAIL, contrasenaIntroducida), CONTEXTO);

        // Then: el codificador recibe siempre la forma NFC y el login es correcto
        verify(codificador).matches(CONTRASENA_CON_ACENTOS_NFC, HASH_USUARIO);
        assertThat(resultado.id()).isEqualTo(ID_USUARIO);
    }

    // ------------------------------------------------------------------ CP-LOG-07

    @Test
    void autenticar_loginCorrectoTrasFallosPrevios_reiniciaLosContadoresDelCorreo() { // CP-LOG-07
        // Given: una cuenta con fallos previos (el limitador está mockeado) y credenciales correctas
        dadoUsuarioRegistrado(usuario(Rol.CLIENTE, EstadoUsuario.ACTIVO));
        dadaContrasenaCorrecta();

        // When
        servicio.autenticar(new SolicitudLoginDTO("  ANA.Garcia@Ejemplo.ES ", CONTRASENA), CONTEXTO);

        // Then: primero se pide paso al limitador, y al acertar se reinician los contadores del correo normalizado
        InOrder orden = inOrder(limitador, repositorioUsuario);
        orden.verify(limitador).comprobarIntento(EMAIL, CONTEXTO.ip());
        orden.verify(repositorioUsuario).buscarPorEmail(EMAIL);
        orden.verify(limitador).registrarExito(EMAIL);
        verify(limitador, never()).registrarFallo(any(), any());
    }
}
