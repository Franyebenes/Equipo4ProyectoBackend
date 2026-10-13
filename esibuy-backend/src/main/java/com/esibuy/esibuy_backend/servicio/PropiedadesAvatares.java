package com.esibuy.esibuy_backend.servicio;

import java.util.List;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Catalogo de avatares (prefijo esibuy.avatares). Se guardan identificadores, no URLs: el frontend sirve la
 * imagen de cada uno (p. ej. public/avatares/&lt;id&gt;.png). Asi un usuario no puede apuntar a una URL externa.
 *
 * Se valida al arrancar: si la configuracion es incorrecta la aplicacion no arranca.
 *
 * @param disponibles        avatares que el usuario puede elegir en el formulario
 * @param porDefectoCliente  avatar asignado al cliente que no elige ninguno
 * @param porDefectoVendedor avatar asignado al vendedor que no elige ninguno (distinto del de cliente)
 */
@ConfigurationProperties(prefix = "esibuy.avatares")
public record PropiedadesAvatares(List<String> disponibles, String porDefectoCliente, String porDefectoVendedor) {

    /** Solo minusculas, digitos y guiones: el id se usa para formar el nombre de un fichero en el frontend. */
    private static final Pattern FORMATO_ID = Pattern.compile("^[a-z0-9-]{1,40}$");

    public PropiedadesAvatares {
        if (disponibles == null || disponibles.isEmpty()) {
            throw new IllegalStateException("esibuy.avatares.disponibles no puede estar vacio");
        }
        disponibles = List.copyOf(disponibles);
        disponibles.forEach(PropiedadesAvatares::comprobarFormato);
        comprobarFormato(porDefectoCliente);
        comprobarFormato(porDefectoVendedor);
        if (porDefectoCliente.equals(porDefectoVendedor)) {
            throw new IllegalStateException("Los avatares por defecto de cliente y vendedor deben ser distintos");
        }
    }

    private static void comprobarFormato(String id) {
        if (id == null || !FORMATO_ID.matcher(id).matches()) {
            throw new IllegalStateException("Identificador de avatar no valido en esibuy.avatares: " + id);
        }
    }
}
