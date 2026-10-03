package com.esibuy.esibuy_backend.servicio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Diccionario que usa el validador: combina las listas locales con Pwned Passwords.
 * <ul>
 *   <li>Comunes: solo la lista local.</li>
 *   <li>Filtradas: primero la lista local (no cuesta nada) y, si no esta ahi, Pwned Passwords.</li>
 * </ul>
 * Si Pwned Passwords no responde, se deja pasar la contrasena con lo que digan las listas locales y se avisa en
 * el log: el registro no debe depender de que un servicio de terceros este disponible (decision del equipo).
 * Con esibuy.pwned-passwords.habilitado=false se usan solo las listas locales (p. ej. para trabajar sin red).
 */
@Primary
@Component
public class DiccionarioContrasenasCompuesto implements DiccionarioContrasenasProhibidas {

    private static final Logger log = LoggerFactory.getLogger(DiccionarioContrasenasCompuesto.class);

    private final DiccionarioContrasenasLocal local;
    private final ClientePwnedPasswords pwnedPasswords;
    private final boolean pwnedPasswordsHabilitado;

    public DiccionarioContrasenasCompuesto(DiccionarioContrasenasLocal local, ClientePwnedPasswords pwnedPasswords,
            @Value("${esibuy.pwned-passwords.habilitado:true}") boolean pwnedPasswordsHabilitado) {
        this.local = local;
        this.pwnedPasswords = pwnedPasswords;
        this.pwnedPasswordsHabilitado = pwnedPasswordsHabilitado;
    }

    @Override
    public boolean esComun(String contrasenaEnMinusculas) {
        return local.esComun(contrasenaEnMinusculas);
    }

    @Override
    public boolean estaFiltrada(String contrasena) {
        return local.estaFiltrada(contrasena) || (pwnedPasswordsHabilitado && estaEnPwnedPasswords(contrasena));
    }

    private boolean estaEnPwnedPasswords(String contrasena) {
        try {
            return pwnedPasswords.estaFiltrada(contrasena);
        } catch (RuntimeException e) {
            // Sin la excepcion completa: basta con el tipo y el mensaje, que no contienen la contrasena
            log.warn("Pwned Passwords no disponible ({}: {}); se comprueba solo con las listas locales",
                    e.getClass().getSimpleName(), e.getMessage());
            return false;
        }
    }
}
