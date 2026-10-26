package com.esibuy.esibuy_backend.seguridad;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/*
 * Ciclo de vida del secreto del 2FA pendiente durante el registro. La persona escanea el QR ANTES de que exista su cuenta, asi
 * que el secreto no puede ir a la base de datos: se guarda cifrado en la sesion HTTP, en memoria, sin tablas ni campos nuevos,
 * junto con el correo para el que se genero. El registro final lo recupera, comprueba el codigo y crea la cuenta con el 2FA
 * activo.
 *
 * Solo hay un pendiente por sesion: pedir otro QR lo sustituye y reinicia los intentos. Caduca a los 10 minutos exactos desde
 * que se genero (los codigos fallidos no lo alargan) y se descarta al llegar al maximo de codigos fallidos (5). No concede nada:
 * solo anade un atributo a la sesion. Una sesion anonima existente (la que guarda el token CSRF) se reutiliza y una
 * autenticada no se invalida (alguien con sesion puede registrar otra cuenta).
 * Tests: GestorRegistroDosFactoresTest (CP-2FA-377 a CP-2FA-396).
 */
public class GestorRegistroDosFactores {

    /** Atributo de sesion con el secreto pendiente (PendienteRegistroDosFactores). */
    public static final String ATRIBUTO_PENDIENTE = "esibuy.registro.dosfactores";

    private final Duration caducidad;
    private final int maxIntentos;
    private final Clock reloj;

    public GestorRegistroDosFactores(Duration caducidad, int maxIntentos, Clock reloj) {
        if (caducidad == null || caducidad.isZero() || caducidad.isNegative()) {
            throw new IllegalArgumentException("La caducidad del secreto pendiente debe ser una duracion positiva");
        }
        if (maxIntentos < 1) {
            throw new IllegalArgumentException("El maximo de intentos debe ser al menos 1");
        }
        if (reloj == null) {
            throw new IllegalArgumentException("El reloj es obligatorio");
        }
        this.caducidad = caducidad;
        this.maxIntentos = maxIntentos;
        this.reloj = reloj;
    }

    /** Guarda el secreto cifrado para ese correo, sustituyendo el anterior si lo habia. */
    public void iniciar(HttpServletRequest peticion, String email, String secretoCifrado) {
        HttpSession sesion = peticion.getSession(true);
        sesion.setAttribute(ATRIBUTO_PENDIENTE, new PendienteRegistroDosFactores(email, secretoCifrado, reloj.instant(), 0));
    }

    /** El pendiente de la sesion si lo hay y no ha caducado. Si ha caducado, lo elimina. No crea ninguna sesion. */
    public Optional<PendienteRegistroDosFactores> vigente(HttpServletRequest peticion) {
        HttpSession sesion = peticion.getSession(false);
        if (sesion == null || !(sesion.getAttribute(ATRIBUTO_PENDIENTE) instanceof PendienteRegistroDosFactores pendiente)) {
            return Optional.empty();
        }
        if (!reloj.instant().isBefore(pendiente.creadoEn().plus(caducidad))) {
            sesion.removeAttribute(ATRIBUTO_PENDIENTE);
            return Optional.empty();
        }
        return Optional.of(pendiente);
    }

    /** Suma un codigo fallido. @return true si con este se agota (y se descarta); false en cualquier otro caso. */
    public boolean registrarFallo(HttpServletRequest peticion) {
        Optional<PendienteRegistroDosFactores> vigente = vigente(peticion);
        if (vigente.isEmpty()) {
            return false;
        }
        PendienteRegistroDosFactores conFallo = vigente.get().conUnFallo();
        HttpSession sesion = peticion.getSession(false);
        if (conFallo.intentosFallidos() >= maxIntentos) {
            sesion.removeAttribute(ATRIBUTO_PENDIENTE);
            return true;
        }
        sesion.setAttribute(ATRIBUTO_PENDIENTE, conFallo);
        return false;
    }

    /** Elimina el pendiente. Es idempotente y no crea ninguna sesion. */
    public void descartar(HttpServletRequest peticion) {
        HttpSession sesion = peticion.getSession(false);
        if (sesion != null) {
            sesion.removeAttribute(ATRIBUTO_PENDIENTE);
        }
    }
}
