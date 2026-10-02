package com.esibuy.esibuy_backend.servicio;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Listas locales de contrasenas prohibidas (las usa {@link DiccionarioContrasenasCompuesto}), cargadas en memoria al arrancar desde dos ficheros del classpath (una
 * contrasena por linea, UTF-8; se ignoran las lineas vacias y las que empiezan por #):
 *   Comunes: se comparan sin distinguir mayusculas, asi que se guardan en minusculas.</li>
 *   Filtradas en brechas: se comparan tal cual.</li>
 * Ambas se normalizan a NFC, igual que la contrasena que llega del validador. Si falta un fichero, la aplicacion
 * no arranca: un diccionario vacio dejaria pasar contrasenas debiles sin avisar.
 */
@Component
public class DiccionarioContrasenasLocal implements DiccionarioContrasenasProhibidas {

    private final Set<String> comunes;
    private final Set<String> filtradas;

    public DiccionarioContrasenasLocal(
            @Value("${esibuy.contrasenas.fichero-comunes:seguridad/contrasenas-comunes.txt}") String ficheroComunes,
            @Value("${esibuy.contrasenas.fichero-filtradas:seguridad/contrasenas-filtradas.txt}")
            String ficheroFiltradas) {
        this.comunes = cargar(ficheroComunes, contrasena -> contrasena.toLowerCase(Locale.ROOT));
        this.filtradas = cargar(ficheroFiltradas, UnaryOperator.identity());
    }

    @Override
    public boolean esComun(String contrasenaEnMinusculas) {
        return comunes.contains(contrasenaEnMinusculas);
    }

    @Override
    public boolean estaFiltrada(String contrasena) {
        return filtradas.contains(contrasena);
    }

    private static Set<String> cargar(String rutaClasspath, UnaryOperator<String> transformacion) {
        ClassPathResource fichero = new ClassPathResource(rutaClasspath);
        if (!fichero.exists()) {
            throw new IllegalStateException("No se encuentra el diccionario de contrasenas: " + rutaClasspath);
        }
        try (BufferedReader lector = new BufferedReader(
                new InputStreamReader(fichero.getInputStream(), StandardCharsets.UTF_8))) {
            return lector.lines()
                    .filter(linea -> !linea.isBlank() && !linea.startsWith("#"))
                    .map(linea -> transformacion.apply(Normalizer.normalize(linea, Normalizer.Form.NFC)))
                    .collect(Collectors.toUnmodifiableSet());
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer el diccionario de contrasenas: " + rutaClasspath, e);
        }
    }
}
