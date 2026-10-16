package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.esibuy.esibuy_backend.dto.SolicitudAltaAdministradorDTO;
import com.esibuy.esibuy_backend.excepcion.CodigoError;
import com.esibuy.esibuy_backend.excepcion.DatosInvalidosException;
import com.esibuy.esibuy_backend.util.DiccionarioContrasenasFalso;

/**
 * Politica de contrasenas aplicada al alta de administradores (CP-PWD-19).
 *
 * El resto de pruebas de la gestion de usuarios mockea el validador; aqui se usa el real (con el diccionario falso en
 * memoria) para comprobar que el alta de administradores exige la misma composicion que el registro.
 */
class ServicioGestionUsuariosPoliticaContrasenaTest extends ServicioGestionUsuariosBase {

    @BeforeEach
    void usarElValidadorReal() {
        servicio = new ServicioGestionUsuarios(repositorioUsuario, repositorioProducto, validadorDominioEmail,
                new ValidadorContrasena(new DiccionarioContrasenasFalso()), codificadorContrasena, catalogoAvatares,
                mongoTemplate, repositorioCategoria, RELOJ);
    }

    @Test
    void crearAdministrador_contrasenaSinComposicion_rechazaEnElCampoContrasenaYNoGuarda() { // CP-PWD-19
        // Given: 12 letras minusculas, sin mayuscula, numero ni caracter especial
        SolicitudAltaAdministradorDTO solicitud = solicitudAlta("abcdefghijkl", "abcdefghijkl");

        // When / Then
        assertThatThrownBy(() -> servicio.crearAdministrador(solicitud))
                .isInstanceOfSatisfying(DatosInvalidosException.class, excepcion ->
                        assertThat(excepcion.getErrores().get("contrasena")).containsExactlyInAnyOrder(
                                CodigoError.CONTRASENA_SIN_MAYUSCULA,
                                CodigoError.CONTRASENA_SIN_NUMERO,
                                CodigoError.CONTRASENA_SIN_CARACTER_ESPECIAL));
        verify(codificadorContrasena, never()).encode(any());
        verify(repositorioUsuario, never()).save(any());
    }
}
