package com.esibuy.esibuy_backend.servicio;

import com.esibuy.esibuy_backend.excepcion.CodigoError;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Politica de contrasenas (decisiones D1, D7 y D9). Tests: ValidadorContrasenaTest (CP-PWD-01 a CP-PWD-12).
 */
@Component
public class ValidadorContrasena {

    public static final int LONGITUD_MINIMA = 12;
    public static final int LONGITUD_MAXIMA = 128;

    private final DiccionarioContrasenasProhibidas diccionario;

    public ValidadorContrasena(DiccionarioContrasenasProhibidas diccionario) {
        this.diccionario = diccionario;
    }

    /**
     * TODO: normalizar a NFC, contar puntos de codigo (no unidades UTF-16), consultar el diccionario
     * (esComun en minusculas, estaFiltrada tal cual), comparar con los datos personales por palabras de
     * 3 o mas letras ignorando mayusculas y acentos, y el nombre de la aplicacion ("esibuy").
     *
     * @return conjunto vacio si la contrasena es valida
     */
    public Set<CodigoError> validar(String contrasena, DatosPersonalesContrasena datosPersonales) {
        throw new UnsupportedOperationException("Pendiente de implementar");
    }
}
