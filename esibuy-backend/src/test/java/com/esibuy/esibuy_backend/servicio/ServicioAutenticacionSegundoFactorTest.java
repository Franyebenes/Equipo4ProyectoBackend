package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import com.esibuy.esibuy_backend.dto.SolicitudLoginDTO;
import com.esibuy.esibuy_backend.excepcion.CredencialesInvalidasException;
import com.esibuy.esibuy_backend.modelo.DosFactores;
import com.esibuy.esibuy_backend.modelo.DosFactores.CodigoRecuperacion;
import com.esibuy.esibuy_backend.modelo.EstadoUsuario;
import com.esibuy.esibuy_backend.modelo.Rol;
import com.esibuy.esibuy_backend.modelo.Usuario;

/**
 * Primer paso del login con el segundo factor (HdU 2FA, fase 3). Casos: CP-2FA-283 a CP-2FA-291.
 *
 * ServicioAutenticacion sigue comprobando solo correo y contrasena. Lo nuevo es que el resultado dice si la cuenta
 * ademas tiene el 2FA activo (requiereSegundoFactor): en ese caso la contrasena correcta todavia NO es un login
 * correcto (no se audita LOGIN_CORRECTO, sino LOGIN_PRIMER_FACTOR_CORRECTO) y el controlador no concede sesion hasta el
 * segundo paso. Una contrasena correcta si limpia los fallos de contrasena del limitador del login; el segundo paso
 * tiene su propio limite (LimitadorDosFactores), asi que un codigo mal puesto no bloquea la contrasena ni al reves.
 *
 * Que la cuenta tiene 2FA solo se revela a quien ya acerto la contrasena de una cuenta ACTIVA: todos los demas rechazos
 * (contrasena incorrecta, cuenta pendiente o bloqueada, correo desconocido) son identicos a los de una cuenta sin 2FA,
 * asi que no abre una via para enumerar cuentas ni para saber quien lo tiene. Los resultados anteriores (sin 2FA) no
 * cambian: los constructores antiguos de ResultadoAutenticacion siguen valiendo y dejan el indicador a falso.
 *
 * Terminada: ya no lleva la etiqueta pendiente-2fa y entra en la regresion.
 */
class ServicioAutenticacionSegundoFactorTest extends ServicioAutenticacionBaseTest {

    private static final String CIFRADO = "1:" + "A".repeat(64);
    private static final Instant ACTIVADO = Instant.parse("2026-10-20T10:00:00Z");

    private static Usuario usuarioConDosFactores(Rol rol, EstadoUsuario estado) {
        Usuario usuario = usuario(rol, estado);
        ReflectionTestUtils.setField(usuario, "dosFactores", DosFactores.activo(CIFRADO, ACTIVADO,
                GeneradorTotp.NINGUN_PASO, List.of(CodigoRecuperacion.nuevo("a".repeat(64)))));
        return usuario;
    }

    private static Usuario usuarioConActivacionPendiente() {
        Usuario usuario = usuario(Rol.CLIENTE, EstadoUsuario.ACTIVO);
        ReflectionTestUtils.setField(usuario, "dosFactores", DosFactores.pendiente(CIFRADO, ACTIVADO));
        return usuario;
    }

    @ParameterizedTest(name = "usuario {0}")
    @EnumSource(Rol.class)
    void autenticar_cuentaActivaConDosFactoresYContrasenaCorrecta_pideElSegundoFactor(Rol rol) { // CP-2FA-283
        dadoUsuarioRegistrado(usuarioConDosFactores(rol, EstadoUsuario.ACTIVO));
        dadaContrasenaCorrecta();

        ResultadoAutenticacion resultado = servicio.autenticar(new SolicitudLoginDTO(EMAIL, CONTRASENA), CONTEXTO);

        // Los mismos datos de siempre (id, correo, nombre, un unico rol), mas el indicador
        assertThat(resultado.requiereSegundoFactor()).isTrue();
        assertThat(resultado.id()).isEqualTo(ID_USUARIO);
        assertThat(resultado.email()).isEqualTo(EMAIL);
        assertThat(resultado.nombre()).isEqualTo(NOMBRE);
        assertThat(resultado.rol()).isEqualTo(rol);
    }

    @Test
    void autenticar_conDosFactores_noSeAuditaComoLoginCorrectoPeroSiComoPrimerFactor() { // CP-2FA-284
        dadoUsuarioRegistrado(usuarioConDosFactores(Rol.CLIENTE, EstadoUsuario.ACTIVO));
        dadaContrasenaCorrecta();

        servicio.autenticar(new SolicitudLoginDTO(EMAIL, CONTRASENA), CONTEXTO);

        verify(auditoria).registrarLoginPrimerFactorCorrecto(CONTEXTO, EMAIL, ID_USUARIO);
        verify(auditoria, never()).registrarLoginCorrecto(any(), anyString(), anyString());
        // La contrasena era buena: se limpian sus fallos en el limitador del login
        verify(limitador).registrarExito(EMAIL);
        verify(limitador, never()).registrarFallo(anyString(), anyString());
    }

    @Test
    void autenticar_cuentaSinDosFactores_seComportaComoSiempre() { // CP-2FA-285
        dadoUsuarioRegistrado(usuario(Rol.CLIENTE, EstadoUsuario.ACTIVO));
        dadaContrasenaCorrecta();

        ResultadoAutenticacion resultado = servicio.autenticar(new SolicitudLoginDTO(EMAIL, CONTRASENA), CONTEXTO);

        assertThat(resultado.requiereSegundoFactor()).isFalse();
        verify(auditoria).registrarLoginCorrecto(CONTEXTO, EMAIL, ID_USUARIO);
        verify(auditoria, never()).registrarLoginPrimerFactorCorrecto(any(), anyString(), anyString());
    }

    @Test
    void autenticar_conUnaActivacionPendienteSinConfirmar_noPideElSegundoFactor() { // CP-2FA-286
        // Mientras no se confirma el primer codigo, el 2FA no esta activo: se entra solo con la contrasena
        dadoUsuarioRegistrado(usuarioConActivacionPendiente());
        dadaContrasenaCorrecta();

        ResultadoAutenticacion resultado = servicio.autenticar(new SolicitudLoginDTO(EMAIL, CONTRASENA), CONTEXTO);

        assertThat(resultado.requiereSegundoFactor()).isFalse();
        verify(auditoria).registrarLoginCorrecto(CONTEXTO, EMAIL, ID_USUARIO);
    }

    @Test
    void autenticar_conDosFactoresPeroContrasenaIncorrecta_esElMismoRechazoDeSiempre() { // CP-2FA-287
        dadoUsuarioRegistrado(usuarioConDosFactores(Rol.CLIENTE, EstadoUsuario.ACTIVO));

        CredencialesInvalidasException error = assertThrows(CredencialesInvalidasException.class,
                () -> servicio.autenticar(new SolicitudLoginDTO(EMAIL, "otra-contrasena"), CONTEXTO));

        // Mismo mensaje y mismo fallo que con una cuenta sin 2FA: no se sabe si la cuenta lo tiene
        assertThat(error.getMessage()).isEqualTo(new CredencialesInvalidasException().getMessage());
        verify(limitador).registrarFallo(EMAIL, CONTEXTO.ip());
        verify(auditoria, never()).registrarLoginPrimerFactorCorrecto(any(), anyString(), anyString());
    }

    @ParameterizedTest(name = "cuenta {0}")
    @EnumSource(value = EstadoUsuario.class, names = { "DESACTIVADO", "BLOQUEADO" })
    void autenticar_conDosFactoresPeroCuentaNoActiva_esElMismoRechazoDeSiempre(EstadoUsuario estado) { // CP-2FA-288
        dadoUsuarioRegistrado(usuarioConDosFactores(Rol.CLIENTE, estado));
        dadaContrasenaCorrecta();

        assertThrows(CredencialesInvalidasException.class,
                () -> servicio.autenticar(new SolicitudLoginDTO(EMAIL, CONTRASENA), CONTEXTO));

        // Nunca se llega a pedir el segundo factor ni se revela que la cuenta lo tiene
        verify(auditoria, never()).registrarLoginPrimerFactorCorrecto(any(), anyString(), anyString());
        verify(auditoria, never()).registrarLoginCorrecto(any(), anyString(), anyString());
    }

    @Test
    void resultadoAutenticacion_losConstructoresAntiguosDejanElIndicadorAFalso() { // CP-2FA-289
        ResultadoAutenticacion cuatro = new ResultadoAutenticacion(ID_USUARIO, EMAIL, NOMBRE, Rol.CLIENTE);
        ResultadoAutenticacion cinco = new ResultadoAutenticacion(ID_USUARIO, EMAIL, NOMBRE, Rol.CLIENTE, "avatar-01");
        ResultadoAutenticacion seis = new ResultadoAutenticacion(ID_USUARIO, EMAIL, NOMBRE, Rol.CLIENTE, "avatar-01", true);

        assertThat(cuatro.requiereSegundoFactor()).isFalse();
        assertThat(cinco.requiereSegundoFactor()).isFalse();
        assertThat(seis.requiereSegundoFactor()).isTrue();
        assertThat(cinco).isEqualTo(new ResultadoAutenticacion(ID_USUARIO, EMAIL, NOMBRE, Rol.CLIENTE, "avatar-01", false))
                .isNotEqualTo(seis);
    }

    @Test
    void resultadoAutenticacion_sigueSiendoSerializableConElIndicador() throws Exception { // CP-2FA-290
        // Se guarda en la sesion, que el contenedor puede persistir al reiniciarse
        ResultadoAutenticacion original = new ResultadoAutenticacion(ID_USUARIO, EMAIL, NOMBRE, Rol.ADMIN, null, true);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream salida = new ObjectOutputStream(bytes)) {
            salida.writeObject(original);
        }
        Object leido;
        try (ObjectInputStream entrada = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            leido = entrada.readObject();
        }

        assertThat(leido).isEqualTo(original);
    }

    @Test
    void autenticar_laRespuestaNoLlevaNingunDatoDelSegundoFactor() { // CP-2FA-291
        dadoUsuarioRegistrado(usuarioConDosFactores(Rol.CLIENTE, EstadoUsuario.ACTIVO));
        dadaContrasenaCorrecta();

        ResultadoAutenticacion resultado = servicio.autenticar(new SolicitudLoginDTO(EMAIL, CONTRASENA), CONTEXTO);

        assertThat(resultado.toString()).doesNotContain(CIFRADO).doesNotContain("a".repeat(64));
    }
}
