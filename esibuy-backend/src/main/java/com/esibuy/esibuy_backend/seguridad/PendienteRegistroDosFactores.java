package com.esibuy.esibuy_backend.seguridad;

import java.io.Serializable;
import java.time.Instant;

/*
 * Secreto del 2FA que se ha generado durante el registro y todavia no se ha usado para crear la cuenta: el correo para el que
 * se genero (ya normalizado), el secreto CIFRADO (nunca en claro), cuando se genero y cuantos codigos fallidos lleva. Es
 * inmutable y Serializable, porque vive en la sesion HTTP y el contenedor puede persistirla al reiniciarse. El toString no
 * incluye el texto cifrado. No concede nada: ver GestorRegistroDosFactores.
 */
public record PendienteRegistroDosFactores(String email, String secretoCifrado, Instant creadoEn, int intentosFallidos)
        implements Serializable {

    /** El mismo pendiente con un codigo fallido mas; no cambia la fecha en que se genero. */
    public PendienteRegistroDosFactores conUnFallo() {
        return new PendienteRegistroDosFactores(email, secretoCifrado, creadoEn, intentosFallidos + 1);
    }

    /** True si se genero para ese correo (ya normalizado): si la persona lo cambia entre los dos pasos, no vale. */
    public boolean esDe(String correoNormalizado) {
        return correoNormalizado != null && !correoNormalizado.isEmpty() && email.equals(correoNormalizado);
    }

    @Override
    public String toString() {
        return "PendienteRegistroDosFactores[creadoEn=" + creadoEn + ", intentosFallidos=" + intentosFallidos + "]";
    }
}
