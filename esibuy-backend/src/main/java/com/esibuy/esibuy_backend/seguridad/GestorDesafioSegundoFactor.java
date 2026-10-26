package com.esibuy.esibuy_backend.seguridad;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

import com.esibuy.esibuy_backend.servicio.ResultadoAutenticacion;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/*
 * Ciclo de vida del desafio pendiente del login con segundo factor. Vive en la sesion HTTP, en memoria como el resto de la
 * sesion: sin tablas ni campos nuevos en la base de datos. Lo esencial es que NO concede nada: la sesion con un desafio
 * pendiente no tiene contexto de seguridad ni los atributos de una sesion autenticada, asi que para el resto de la
 * aplicacion sigue siendo anonima (401 en /api/auth/me y en todo lo protegido).
 *
 * Caduca a los 5 minutos exactos desde que empezo (los codigos fallidos no lo alargan) y se descarta al llegar al maximo de
 * codigos fallidos (5). Si el primer paso se repite, el desafio se sustituye y los intentos vuelven a cero: lo acota el limite
 * por cuenta del segundo factor, que no depende de la sesion.
 *
 * Una sesion anonima ya existente (la que guarda el token CSRF) se reutiliza, para que el token siga valiendo en el segundo
 * paso; una autenticada (de otro usuario) se invalida y se crea otra. La sesion definitiva la crea EstablecedorSesion al
 * acertar el codigo, que ya invalida la anterior y evita la fijacion de sesion.
 * Tests: GestorDesafioSegundoFactorTest (CP-2FA-292 a CP-2FA-311).
 */
public class GestorDesafioSegundoFactor {

    /** Atributo de sesion con el desafio pendiente (DesafioSegundoFactor). */
    public static final String ATRIBUTO_DESAFIO = "esibuy.sesion.desafio2fa";

    private final Duration caducidad;
    private final int maxIntentos;
    private final Clock reloj;

    public GestorDesafioSegundoFactor(Duration caducidad, int maxIntentos, Clock reloj) {
        if (caducidad == null || caducidad.isZero() || caducidad.isNegative()) {
            throw new IllegalArgumentException("La caducidad del desafio debe ser una duracion positiva");
        }
        if (maxIntentos < 1) {
            throw new IllegalArgumentException("El maximo de intentos del desafio debe ser al menos 1");
        }
        if (reloj == null) {
            throw new IllegalArgumentException("El reloj es obligatorio");
        }
        this.caducidad = caducidad;
        this.maxIntentos = maxIntentos;
        this.reloj = reloj;
    }

    /** Guarda un desafio nuevo para ese usuario (sustituyendo el anterior) sin conceder sesion. */
    public void iniciar(HttpServletRequest peticion, ResultadoAutenticacion usuario) {
        HttpSession sesion = peticion.getSession(false);
        if (sesion != null && estaAutenticada(sesion)) {
            // Alguien con la sesion de otro usuario abierta: no se le deja heredarla
            sesion.invalidate();
            sesion = null;
        }
        if (sesion == null) {
            sesion = peticion.getSession(true);
        }
        sesion.setAttribute(ATRIBUTO_DESAFIO, new DesafioSegundoFactor(usuario, reloj.instant(), 0));
        sesion.setMaxInactiveInterval((int) caducidad.toSeconds());
    }

    /** El desafio de la sesion si lo hay y no ha caducado. Si ha caducado, lo elimina. No crea ninguna sesion. */
    public Optional<DesafioSegundoFactor> vigente(HttpServletRequest peticion) {
        HttpSession sesion = peticion.getSession(false);
        if (sesion == null || !(sesion.getAttribute(ATRIBUTO_DESAFIO) instanceof DesafioSegundoFactor desafio)) {
            return Optional.empty();
        }
        Instant limite = desafio.creadoEn().plus(caducidad);
        if (!reloj.instant().isBefore(limite)) {
            sesion.removeAttribute(ATRIBUTO_DESAFIO);
            return Optional.empty();
        }
        return Optional.of(desafio);
    }

    /** Suma un codigo fallido. @return true si con este se agota el desafio (y se descarta); false en cualquier otro caso. */
    public boolean registrarFallo(HttpServletRequest peticion) {
        Optional<DesafioSegundoFactor> vigente = vigente(peticion);
        if (vigente.isEmpty()) {
            return false;
        }
        DesafioSegundoFactor conFallo = vigente.get().conUnFallo();
        HttpSession sesion = peticion.getSession(false);
        if (conFallo.intentosFallidos() >= maxIntentos) {
            sesion.removeAttribute(ATRIBUTO_DESAFIO);
            return true;
        }
        sesion.setAttribute(ATRIBUTO_DESAFIO, conFallo);
        return false;
    }

    /** Elimina el desafio. Es idempotente y no crea ninguna sesion. */
    public void descartar(HttpServletRequest peticion) {
        HttpSession sesion = peticion.getSession(false);
        if (sesion != null) {
            sesion.removeAttribute(ATRIBUTO_DESAFIO);
        }
    }

    private static boolean estaAutenticada(HttpSession sesion) {
        return sesion.getAttribute(EstablecedorSesion.ATRIBUTO_USUARIO) != null
                || sesion.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) != null;
    }
}
