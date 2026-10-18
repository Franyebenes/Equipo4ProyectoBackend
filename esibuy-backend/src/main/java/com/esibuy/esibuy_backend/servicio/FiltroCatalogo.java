package com.esibuy.esibuy_backend.servicio;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

import com.esibuy.esibuy_backend.modelo.Producto;

/**
 * Criterios con los que el vendedor busca en su catalogo propio. Un criterio vacio no filtra: sin criterios se ve
 * todo el catalogo. La busqueda por nombre admite el nombre completo o una parte, sin distinguir mayusculas ni tildes.
 */
public record FiltroCatalogo(String busqueda) {

    private static final Pattern MARCAS_DIACRITICAS = Pattern.compile("\\p{M}");

    /** Sin criterios: todo el catalogo del vendedor. */
    public static FiltroCatalogo sinFiltros() {
        return new FiltroCatalogo(null);
    }

    /** true si el producto cumple todos los criterios indicados. */
    public boolean admite(Producto producto) {
        return coincideNombre(producto.getNombre());
    }

    private boolean coincideNombre(String nombre) {
        if (busqueda == null || busqueda.isBlank()) {
            return true;
        }
        return nombre != null && normalizar(nombre).contains(normalizar(busqueda));
    }

    /** Minusculas y sin tildes ni dieresis, para que "Lámpara" y "lampara" se comparen igual. */
    static String normalizar(String texto) {
        String sinTildes = MARCAS_DIACRITICAS.matcher(Normalizer.normalize(texto, Normalizer.Form.NFD)).replaceAll("");
        return sinTildes.toLowerCase(Locale.ROOT).strip();
    }
}
