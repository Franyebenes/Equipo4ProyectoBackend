package com.esibuy.esibuy_backend.servicio;

import java.time.Clock;

import org.springframework.security.crypto.password.PasswordEncoder;

import com.esibuy.esibuy_backend.dto.SolicitudLoginDTO;
import com.esibuy.esibuy_backend.excepcion.CodigoError;
import com.esibuy.esibuy_backend.excepcion.DatosLoginInvalidosException;
import com.esibuy.esibuy_backend.repositorio.RepositorioUsuario;
import com.esibuy.esibuy_backend.util.Constantes;

/**
 * Autenticacion por correo y contrasena. Tests: ServicioAutenticacion*Test.
 *
 * <p>Implementado hasta ahora (fase 1): validacion y normalizacion de las credenciales, que se hace antes de tocar
 * la BBDD, el codificador o el limitador. Unas credenciales mal formadas son un 400 y no cuentan como intento
 * fallido. El resto del flujo (verificar la contrasena, limitador, auditoria, resultado) llega en las fases 2 a 4.
 *
 * <p>Aun no es un bean de Spring (sin @Service): se registrara al conectarlo con el controlador en la fase 6, para
 * no alterar el contexto de la aplicacion mientras tanto.
 */
public class ServicioAutenticacion {

    // Nombres de campo de los errores: coinciden con los del JSON de la solicitud
    private static final String CAMPO_EMAIL = "email";
    private static final String CAMPO_CONTRASENA = "contrasena";

    private final RepositorioUsuario repositorioUsuario;
    private final PasswordEncoder codificadorContrasena;
    private final LimitadorIntentosLogin limitador;
    private final AuditoriaSeguridad auditoria;
    private final Clock reloj;

    public ServicioAutenticacion(RepositorioUsuario repositorioUsuario,
                                 PasswordEncoder codificadorContrasena,
                                 LimitadorIntentosLogin limitador,
                                 AuditoriaSeguridad auditoria,
                                 Clock reloj) {
        this.repositorioUsuario = repositorioUsuario;
        this.codificadorContrasena = codificadorContrasena;
        this.limitador = limitador;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    public ResultadoAutenticacion autenticar(SolicitudLoginDTO solicitud, ContextoPeticion contexto) {
        String email = NormalizadorRegistro.email(solicitud.email());
        String contrasena = NormalizadorRegistro.contrasena(solicitud.contrasena());
        validarCredenciales(email, contrasena);

        repositorioUsuario.buscarPorEmail(email);
        throw new UnsupportedOperationException("Pendiente de implementar");
    }

    /**
     * Mismas reglas del correo que el registro (obligatorio, hasta 254 caracteres, formato). La contrasena solo
     * debe venir informada y no pasarse de longitud: su fortaleza no se valida al entrar, para no responder con un
     * 400 que distinga una contrasena debil de una incorrecta. Se devuelven todos los errores a la vez.
     */
    private static void validarCredenciales(String email, String contrasena) {
        ErroresRegistro errores = new ErroresRegistro();
        ReglasCamposRegistro.validarEmail(CAMPO_EMAIL, email, errores);
        if (contrasena == null || contrasena.isBlank()) {
            errores.anadir(CAMPO_CONTRASENA, CodigoError.OBLIGATORIO);
        } else if (contrasena.codePointCount(0, contrasena.length()) > Constantes.LONGITUD_MAXIMA) {
            // Caracteres reales, no unidades UTF-16: un emoji cuenta como 1
            errores.anadir(CAMPO_CONTRASENA, CodigoError.LONGITUD_EXCESIVA);
        }
        if (errores.hayErrores()) {
            throw new DatosLoginInvalidosException(errores.comoMapa());
        }
    }
}
