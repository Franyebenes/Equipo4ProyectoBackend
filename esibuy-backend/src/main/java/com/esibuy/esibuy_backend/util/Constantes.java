package com.esibuy.esibuy_backend.util;

/** Constantes compartidas de la aplicacion. */
public final class Constantes {

    // Validador de contrasena
    public static final int LONGITUD_MINIMA = 12;
    public static final int LONGITUD_MAXIMA = 128;
    public static final int LONGITUD_MINIMA_DATO_PERSONAL = 3;
    public static final String NOMBRE_APLICACION = "esibuy";

    // Caracteres permitidos en el nombre y la descripcion de categorias y productos (sin caracteres especiales).
    // Las mismas reglas estan en el frontend (core/caracteres-permitidos.ts).
    /** Nombre: letras (tambien con tilde, ñ o ü), numeros y espacios. */
    public static final String PATRON_NOMBRE = "^[\\p{L}\\d ]+$";
    /** Descripcion: lo mismo que el nombre, saltos de linea y los signos de puntuacion . , ; : ¿ ? ¡ ! ( ) - */
    public static final String PATRON_DESCRIPCION = "^[\\p{L}\\d \\t\\r\\n.,;:¿?¡!()-]+$";
    public static final String MENSAJE_NOMBRE_NO_PERMITIDO =
            "El nombre solo puede contener letras, números y espacios";
    public static final String MENSAJE_DESCRIPCION_NO_PERMITIDA =
            "La descripción solo puede contener letras, números, espacios y los signos . , ; : ¿ ? ¡ ! ( ) -";

    private Constantes() {
    }
}