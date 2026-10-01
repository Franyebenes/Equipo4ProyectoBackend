package com.esibuy.esibuy_backend.servicio;

import com.esibuy.esibuy_backend.dto.SolicitudRegistroClienteDTO;
import com.esibuy.esibuy_backend.dto.SolicitudRegistroVendedorDTO;
import com.esibuy.esibuy_backend.excepcion.CodigoError;
import com.esibuy.esibuy_backend.excepcion.DatosRegistroInvalidosException;
import com.esibuy.esibuy_backend.excepcion.RegistroNoCompletadoException;
import com.esibuy.esibuy_backend.util.ConstructorSolicitudCliente;
import com.esibuy.esibuy_backend.util.ConstructorSolicitudVendedor;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unicidad del email (en toda la BBDD, sin importar el tipo de usuario), del nombre comercial
 * y existencia de la categoria principal.
 */
class ServicioRegistroUnicidadTest extends ServicioRegistroBaseTest {

    private static final String EMAIL_CLIENTE = ConstructorSolicitudCliente.EMAIL_POR_DEFECTO;
    private static final String EMAIL_VENDEDOR = ConstructorSolicitudVendedor.EMAIL_POR_DEFECTO;

    // ------------------------------------------------------------------ Email duplicado

    @Test
    void registrarCliente_emailYaRegistrado_lanzaRegistroNoCompletadoSinCodificarNiGuardar() { // CP-REG-38
        // Given
        when(repositorioUsuario.existePorEmail(EMAIL_CLIENTE)).thenReturn(true);
        SolicitudRegistroClienteDTO solicitud = ConstructorSolicitudCliente.unaSolicitudValida().construir();

        // When
        assertThrows(RegistroNoCompletadoException.class, () -> servicio.registrarCliente(solicitud));

        // Then
        verifyNoInteractions(codificador);
        verificarQueNoSeGuardoNingunUsuario();
    }

    @Test
    void registrarCliente_mismoEmailConOtraCapitalizacion_seDetectaComoDuplicado() { // CP-REG-39
        // Given: en la BBDD esta guardado en minusculas
        when(repositorioUsuario.existePorEmail(EMAIL_CLIENTE)).thenReturn(true);
        SolicitudRegistroClienteDTO solicitud = ConstructorSolicitudCliente.unaSolicitudValida()
                .conEmail("ANA.GARCIA@EJEMPLO.ES").construir();

        // When
        assertThrows(RegistroNoCompletadoException.class, () -> servicio.registrarCliente(solicitud));

        // Then
        verificarQueNoSeGuardoNingunUsuario();
    }

    @Test
    void registrarVendedor_emailUsadoPorUnCliente_seRechazaIgual() { // CP-REG-40
        // Given: el email del vendedor ya pertenece a una cuenta (de cualquier tipo)
        when(repositorioUsuario.existePorEmail(EMAIL_VENDEDOR)).thenReturn(true);
        SolicitudRegistroVendedorDTO solicitud = ConstructorSolicitudVendedor.unaSolicitudValida().construir();

        // When
        assertThrows(RegistroNoCompletadoException.class, () -> servicio.registrarVendedor(solicitud));

        // Then
        verificarQueNoSeGuardoNingunUsuario();
    }

    @Test
    void registrarCliente_emailUsadoPorUnVendedor_seRechazaIgual() { // CP-REG-40
        // Given
        when(repositorioUsuario.existePorEmail(EMAIL_VENDEDOR)).thenReturn(true);
        SolicitudRegistroClienteDTO solicitud = ConstructorSolicitudCliente.unaSolicitudValida()
                .conEmail(EMAIL_VENDEDOR).construir();

        // When
        assertThrows(RegistroNoCompletadoException.class, () -> servicio.registrarCliente(solicitud));

        // Then
        verificarQueNoSeGuardoNingunUsuario();
    }

    @Test
    void registrarCliente_condicionDeCarreraConDuplicateKey_seTraduceARegistroNoCompletado() { // CP-REG-41
        // Given: la comprobacion previa pasa, pero otro registro simultaneo gana la carrera
        doThrow(new DuplicateKeyException("E11000 duplicate key error collection: ESIBuy.users index: email_1"))
                .when(repositorioUsuario).save(any());
        SolicitudRegistroClienteDTO solicitud = ConstructorSolicitudCliente.unaSolicitudValida().construir();

        // When
        RegistroNoCompletadoException excepcion =
                assertThrows(RegistroNoCompletadoException.class, () -> servicio.registrarCliente(solicitud));

        // Then
        assertThat(excepcion.getCause()).as("no debe arrastrar el detalle interno de MongoDB").isNull();
    }

    @Test
    void registrarCliente_emailDuplicadoPorTresCaminos_devuelveSiempreElMismoMensajeGenerico() { // CP-REG-47
        // Given / When: camino 1, duplicado detectado en la comprobacion previa
        when(repositorioUsuario.existePorEmail(EMAIL_CLIENTE)).thenReturn(true);
        RegistroNoCompletadoException porComprobacionPrevia = assertThrows(RegistroNoCompletadoException.class,
                () -> servicio.registrarCliente(ConstructorSolicitudCliente.unaSolicitudValida().construir()));

        // camino 2, mismo email con otra capitalizacion
        RegistroNoCompletadoException porMayusculas = assertThrows(RegistroNoCompletadoException.class,
                () -> servicio.registrarCliente(ConstructorSolicitudCliente.unaSolicitudValida()
                        .conEmail("ANA.GARCIA@EJEMPLO.ES").construir()));

        // camino 3, condicion de carrera (la comprobacion previa no lo ve)
        doReturn(false).when(repositorioUsuario).existePorEmail(anyString());
        doThrow(new DuplicateKeyException("E11000 duplicate key")).when(repositorioUsuario).save(any());
        RegistroNoCompletadoException porCarrera = assertThrows(RegistroNoCompletadoException.class,
                () -> servicio.registrarCliente(ConstructorSolicitudCliente.unaSolicitudValida().construir()));

        // Then
        assertThat(porComprobacionPrevia.getMessage())
                .isEqualTo(RegistroNoCompletadoException.MENSAJE_GENERICO)
                .isEqualTo(porMayusculas.getMessage())
                .isEqualTo(porCarrera.getMessage());
        assertThat(porComprobacionPrevia.getMessage())
                .doesNotContain(EMAIL_CLIENTE)
                .doesNotContainIgnoringCase("ya registrado")
                .doesNotContainIgnoringCase("ya existe");
        assertThat(porMayusculas).hasSameClassAs(porComprobacionPrevia);
        assertThat(porCarrera).hasSameClassAs(porComprobacionPrevia);
    }


    // ------------------------------------------------------------------ Nombre comercial duplicado

    @Test
    void registrarVendedor_nombreComercialYaExistente_rechazaConErrorEnElNombreComercial() { // CP-REG-42
        // Given
        when(repositorioUsuario.existePorNombreComercial("tienda norte")).thenReturn(true);
        SolicitudRegistroVendedorDTO solicitud = ConstructorSolicitudVendedor.unaSolicitudValida().construir();

        // When
        DatosRegistroInvalidosException excepcion =
                assertThrows(DatosRegistroInvalidosException.class, () -> servicio.registrarVendedor(solicitud));

        // Then
        assertThat(excepcion.getErrores().get("nombreComercial")).contains(CodigoError.NOMBRE_COMERCIAL_DUPLICADO);
        verificarQueNoSeGuardoNingunUsuario();
    }

    @ParameterizedTest(name = "nombre comercial equivalente: \"{0}\"")
    @ValueSource(strings = {"TIENDA NORTE", "  tienda   norte ", "Tienda\tNorte"})
    void registrarVendedor_nombreComercialDuplicadoPorMayusculasOEspacios_tambienSeRechaza( // CP-REG-43
            String variante) {
        // Given: el repositorio compara con el nombre normalizado (minusculas y espacios colapsados)
        when(repositorioUsuario.existePorNombreComercial("tienda norte")).thenReturn(true);
        SolicitudRegistroVendedorDTO solicitud = ConstructorSolicitudVendedor.unaSolicitudValida()
                .conNombreComercial(variante).construir();

        // When
        DatosRegistroInvalidosException excepcion =
                assertThrows(DatosRegistroInvalidosException.class, () -> servicio.registrarVendedor(solicitud));

        // Then
        assertThat(excepcion.getErrores().get("nombreComercial")).contains(CodigoError.NOMBRE_COMERCIAL_DUPLICADO);
        verificarQueNoSeGuardoNingunUsuario();
    }

    // ------------------------------------------------------------------ Categoria principal

    @Test
    void registrarVendedor_categoriaPrincipalInexistente_rechazaYNoGuardaCategoriaHuerfana() { // CP-REG-44
        // Given: ObjectId con formato valido pero que no existe en la coleccion categories
        String categoriaInexistente = "000000000000000000000000";
        when(repositorioCategoria.existsById(categoriaInexistente)).thenReturn(false);
        SolicitudRegistroVendedorDTO solicitud = ConstructorSolicitudVendedor.unaSolicitudValida()
                .conCategoriaPrincipalId(categoriaInexistente).construir();

        // When
        DatosRegistroInvalidosException excepcion =
                assertThrows(DatosRegistroInvalidosException.class, () -> servicio.registrarVendedor(solicitud));

        // Then
        assertThat(excepcion.getErrores().get("categoriaPrincipalId")).contains(CodigoError.CATEGORIA_INEXISTENTE);
        verificarQueNoSeGuardoNingunUsuario();
    }

    @ParameterizedTest(name = "id de categoria malformado: \"{0}\"")
    @ValueSource(strings = {"no-es-un-objectid", "123", "64b7f0c2a1b2c3d4e5f6071", "64b7f0c2a1b2c3d4e5f6071Z"})
    void registrarVendedor_categoriaPrincipalConIdMalformado_rechazaSinConsultarElRepositorio( // CP-REG-45
            String idMalformado) {
        // Given
        SolicitudRegistroVendedorDTO solicitud = ConstructorSolicitudVendedor.unaSolicitudValida()
                .conCategoriaPrincipalId(idMalformado).construir();

        // When
        DatosRegistroInvalidosException excepcion =
                assertThrows(DatosRegistroInvalidosException.class, () -> servicio.registrarVendedor(solicitud));

        // Then
        assertThat(excepcion.getErrores().get("categoriaPrincipalId")).contains(CodigoError.FORMATO_INVALIDO);
        verify(repositorioCategoria, never()).existsById(any());
        verificarQueNoSeGuardoNingunUsuario();
    }
}