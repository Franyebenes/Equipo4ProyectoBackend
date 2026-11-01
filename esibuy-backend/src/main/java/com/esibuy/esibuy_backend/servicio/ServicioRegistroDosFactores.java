package com.esibuy.esibuy_backend.servicio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.esibuy.esibuy_backend.excepcion.ServicioNoDisponibleException;

/*
 * Primer paso del 2FA en el registro: genera el secreto TOTP para un correo, lo cifra y devuelve lo necesario para el QR. El
 * controlador guarda el texto cifrado en la sesion (GestorRegistroDosFactores) y el registro final lo recupera.
 * Tests: ServicioRegistroDosFactoresTest (CP-2FA-397 a CP-2FA-410).
 *
 * No toca la base de datos (la cuenta todavia no existe) ni comprueba si el correo esta registrado: la respuesta es la misma
 * exista o no, asi que no permite averiguar que correos hay (la unicidad se comprueba, con la respuesta generica de siempre, en
 * el registro final). El correo solo se valida en su formato, con los mismos errores que el registro, y se normaliza igual (sin
 * espacios en los extremos y en minusculas). El secreto se cifra con el correo normalizado como dato asociado: un secreto pedido
 * para un correo no se puede descifrar como si fuera de otro. Un fallo del cifrado es un 503 sin detalles y sin mas log que el
 * nombre de la clase de la excepcion.
 */
@Service
public class ServicioRegistroDosFactores {

    private static final Logger log = LoggerFactory.getLogger(ServicioRegistroDosFactores.class);

    private static final String CAMPO_EMAIL = "email";

    private final CifradorSecretos cifrador;
    private final GeneradorTotp totp;
    private final AuditoriaSeguridad auditoria;
    private final PropiedadesDosFactores propiedades;

    public ServicioRegistroDosFactores(CifradorSecretos cifrador, GeneradorTotp totp, AuditoriaSeguridad auditoria,
            PropiedadesDosFactores propiedades) {
        this.cifrador = cifrador;
        this.totp = totp;
        this.auditoria = auditoria;
        this.propiedades = propiedades;
    }

    public PreparacionRegistroDosFactores preparar(String email, ContextoPeticion contexto) {
        String correo = Normalizador.email(email);
        ErroresValidacion errores = new ErroresValidacion();
        ReglasCampos.validarEmail(CAMPO_EMAIL, correo, errores);
        errores.lanzarSiHay();

        byte[] secreto = totp.nuevoSecreto();
        String cifrado;
        try {
            cifrado = cifrador.cifrar(secreto, correo);
        } catch (RuntimeException e) {
            log.error("Fallo interno al preparar el 2FA del registro: {}", e.getClass().getSimpleName());
            throw new ServicioNoDisponibleException(null);
        }
        auditoria.registrarRegistroDosFactoresIniciado(contexto, correo);
        return new PreparacionRegistroDosFactores(correo, cifrado,
                UriOtpauth.construir(propiedades.emisor(), correo, secreto), Base32.codificar(secreto));
    }
}
