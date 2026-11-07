package com.esibuy.esibuy_backend.servicio;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.esibuy.esibuy_backend.modelo.PrecioProducto;
import com.esibuy.esibuy_backend.modelo.Producto;

/**
 * Criterios con los que el vendedor busca y filtra su catalogo propio. Se pueden combinar y un producto tiene que
 * cumplirlos todos; un criterio vacio no filtra, asi que sin criterios se ve todo el catalogo.
 * <ul>
 *   <li>busqueda: nombre completo o parcial, sin distinguir mayusculas ni tildes.</li>
 *   <li>idCategoria: el producto pertenece a esa categoria (puede tener varias).</li>
 *   <li>activo: true solo los activos (visibles para los clientes), false solo los inactivos.</li>
 *   <li>precioMinimo / precioMaximo: rango del precio que pagan los clientes (con el descuento del producto
 *   aplicado), con los limites incluidos.</li>
 * </ul>
 */
public record FiltroCatalogo(String busqueda, String idCategoria, Boolean activo, Double precioMinimo,
                             Double precioMaximo) {

    private static final Pattern MARCAS_DIACRITICAS = Pattern.compile("\\p{M}");

    /** Sin criterios: todo el catalogo del vendedor. */
    public static FiltroCatalogo sinFiltros() {
        return new FiltroCatalogo(null, null, null, null, null);
    }

    /** true si el producto cumple todos los criterios indicados. */
    public boolean admite(Producto producto) {
        return coincideNombre(producto.getNombre())
                && coincideCategoria(producto.getIdCategorias())
                && coincideEstado(producto.getVisible())
                && coincidePrecio(producto.getPrecio());
    }

    private boolean coincideNombre(String nombre) {
        if (vacio(busqueda)) {
            return true;
        }
        return nombre != null && normalizar(nombre).contains(normalizar(busqueda));
    }

    private boolean coincideCategoria(List<String> idCategorias) {
        if (vacio(idCategoria)) {
            return true;
        }
        return idCategorias != null && idCategorias.contains(idCategoria);
    }

    // Un producto sin el campo visible se trata como inactivo
    private boolean coincideEstado(Boolean visible) {
        return activo == null || activo.equals(Boolean.TRUE.equals(visible));
    }

    // Se compara el precio con descuento, calculado igual que en el carrito para un cliente (redondeado a
    // centimos). Sin descuento es el precio base.
    private boolean coincidePrecio(PrecioProducto precio) {
        if (precioMinimo == null && precioMaximo == null) {
            return true;
        }
        if (precio == null || precio.getBase() == null) {
            return false;
        }
        double precioFinal = ServicioCarrito.precioFinal(precio, false);
        return (precioMinimo == null || precioFinal >= precioMinimo)
                && (precioMaximo == null || precioFinal <= precioMaximo);
    }

    private static boolean vacio(String texto) {
        return texto == null || texto.isBlank();
    }

    /** Minusculas y sin tildes ni dieresis, para que "Lámpara" y "lampara" se comparen igual. */
    static String normalizar(String texto) {
        String sinTildes = MARCAS_DIACRITICAS.matcher(Normalizer.normalize(texto, Normalizer.Form.NFD)).replaceAll("");
        return sinTildes.toLowerCase(Locale.ROOT).strip();
    }
}
