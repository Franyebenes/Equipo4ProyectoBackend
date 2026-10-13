package com.esibuy.esibuy_backend.servicio;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.esibuy.esibuy_backend.dto.SolicitudRegistroClienteDTO;
import com.esibuy.esibuy_backend.excepcion.CodigoError;
import com.esibuy.esibuy_backend.excepcion.DatosRegistroInvalidosException;
import com.esibuy.esibuy_backend.util.ConstructorSolicitudCliente;

/**
 * Mayoria de edad y validez de la fecha de nacimiento.
 *
 * Fecha "de hoy" por defecto en los tests: 2026-10-01 (RELOJ_FIJO de la clase base).
 * El servicio debe calcular la edad con la zona horaria de la aplicacion (Europe/Madrid),
 * no con la del servidor ni la del reloj inyectado.
 */
class ServicioRegistroEdadTest extends ServicioRegistroBaseTest {

    private SolicitudRegistroClienteDTO solicitudNacidoEl(LocalDate fechaNacimiento) {
        return ConstructorSolicitudCliente.unaSolicitudValida().conFechaNacimiento(fechaNacimiento).construir();
    }

    /** Reloj fijado a mediodia del dia indicado, en la zona de la aplicacion. */
    private static Clock relojAlMediodiaDel(String fechaIso) {
        Instant instante = LocalDate.parse(fechaIso).atTime(12, 0).atZone(ZONA_APLICACION).toInstant();
        return Clock.fixed(instante, ZONA_APLICACION);
    }

    private DatosRegistroInvalidosException registrarEsperandoRechazo(ServicioRegistro servicioAUsar,
                                                                      SolicitudRegistroClienteDTO solicitud) {
        return assertThrows(DatosRegistroInvalidosException.class, () -> servicioAUsar.registrarCliente(solicitud));
    }

    @Test
    void registrarCliente_cumpleDieciochoHoy_seAcepta() { // CP-REG-30
        // Given: hoy es 2026-10-01 y nacio el 2008-10-01
        SolicitudRegistroClienteDTO solicitud = solicitudNacidoEl(LocalDate.of(2008, 10, 1));

        // When
        servicio.registrarCliente(solicitud);

        // Then
        assertThat(usuarioGuardado().getPerfil().getFechaNacimiento()).isEqualTo(LocalDate.of(2008, 10, 1));
    }

    @Test
    void registrarCliente_cumpleDieciochoManana_rechazaPorMenorDeEdad() { // CP-REG-31
        // Given: tiene 17 anios y 364 dias
        SolicitudRegistroClienteDTO solicitud = solicitudNacidoEl(LocalDate.of(2008, 10, 2));

        // When
        DatosRegistroInvalidosException excepcion = registrarEsperandoRechazo(servicio, solicitud);

        // Then
        assertThat(excepcion.getErrores().get("fechaNacimiento")).contains(CodigoError.MENOR_DE_EDAD);
        verificarQueNoSeGuardoNingunUsuario();
    }

    @Test
    void registrarCliente_menorEvidenteDeQuinceAnios_rechazaPorMenorDeEdad() { // CP-REG-32
        // Given
        SolicitudRegistroClienteDTO solicitud = solicitudNacidoEl(LocalDate.of(2011, 10, 1));

        // When
        DatosRegistroInvalidosException excepcion = registrarEsperandoRechazo(servicio, solicitud);

        // Then
        assertThat(excepcion.getErrores().get("fechaNacimiento")).contains(CodigoError.MENOR_DE_EDAD);
        verificarQueNoSeGuardoNingunUsuario();
    }

    @Test
    void registrarCliente_fechaDeNacimientoFutura_rechazaPorFechaInvalida() { // CP-REG-33
        // Given: nace "manana"
        SolicitudRegistroClienteDTO solicitud = solicitudNacidoEl(LocalDate.of(2026, 10, 2));

        // When
        DatosRegistroInvalidosException excepcion = registrarEsperandoRechazo(servicio, solicitud);

        // Then
        assertThat(excepcion.getErrores().get("fechaNacimiento")).contains(CodigoError.FECHA_INVALIDA);
        verificarQueNoSeGuardoNingunUsuario();
    }

    @ParameterizedTest(name = "nacido el 2008-02-29, hoy es {0}: se acepta = {1}")
    @CsvSource({
            "2026-02-28, false",   // aun no ha cumplido 18 (en anio no bisiesto cumple el 1 de marzo)
            "2026-03-01, true"     // ya ha cumplido 18
    })
    void registrarCliente_nacidoUnVeintinueveDeFebrero_calculaBienLaEdadEnAnioNoBisiesto( // CP-REG-34
            String hoy, boolean seAcepta) {
        // Given
        ServicioRegistro servicioConReloj = crearServicio(relojAlMediodiaDel(hoy));
        SolicitudRegistroClienteDTO solicitud = solicitudNacidoEl(LocalDate.of(2008, 2, 29));

        // When / Then
        if (seAcepta) {
            servicioConReloj.registrarCliente(solicitud);
            assertThat(usuarioGuardado().getPerfil().getFechaNacimiento()).isEqualTo(LocalDate.of(2008, 2, 29));
        } else {
            DatosRegistroInvalidosException excepcion = registrarEsperandoRechazo(servicioConReloj, solicitud);
            assertThat(excepcion.getErrores().get("fechaNacimiento")).contains(CodigoError.MENOR_DE_EDAD);
            verificarQueNoSeGuardoNingunUsuario();
        }
    }

    @Test
    void registrarCliente_nacidoUnVeintinueveDeFebreroConRelojEnAnioBisiesto_seAcepta() { // CP-REG-34
        // Given: hoy es 2028-02-29 (anio bisiesto) y el usuario tiene 20 anios
        ServicioRegistro servicioConReloj = crearServicio(relojAlMediodiaDel("2028-02-29"));
        SolicitudRegistroClienteDTO solicitud = solicitudNacidoEl(LocalDate.of(2008, 2, 29));

        // When
        servicioConReloj.registrarCliente(solicitud);

        // Then
        assertThat(usuarioGuardado().getPerfil().getFechaNacimiento()).isEqualTo(LocalDate.of(2008, 2, 29));
    }

    @Test
    void registrarCliente_fechaAbsurdaDelAnio1850_rechazaPorFechaInvalida() { // CP-REG-35
        // Given
        SolicitudRegistroClienteDTO solicitud = solicitudNacidoEl(LocalDate.of(1850, 1, 1));

        // When
        DatosRegistroInvalidosException excepcion = registrarEsperandoRechazo(servicio, solicitud);

        // Then
        assertThat(excepcion.getErrores().get("fechaNacimiento")).contains(CodigoError.FECHA_INVALIDA);
        verificarQueNoSeGuardoNingunUsuario();
    }

    @ParameterizedTest(name = "zona del reloj: {0}")
    @ValueSource(strings = {"UTC", "Asia/Tokyo", "America/New_York", "Europe/Madrid"})
    void registrarCliente_cumpleDieciochoEnMadridPasadaLaMedianoche_seAceptaSinImportarLaZonaDelReloj( // CP-REG-37
            String zonaDelReloj) {
        // Given: el instante es 2026-10-01T22:30Z, que en Madrid ya es 2026-10-02 (00:30)
        Clock reloj = Clock.fixed(Instant.parse("2026-10-01T22:30:00Z"), ZoneId.of(zonaDelReloj));
        ServicioRegistro servicioConReloj = crearServicio(reloj);
        SolicitudRegistroClienteDTO solicitud = solicitudNacidoEl(LocalDate.of(2008, 10, 2));

        // When
        servicioConReloj.registrarCliente(solicitud);

        // Then
        assertThat(usuarioGuardado().getPerfil().getFechaNacimiento()).isEqualTo(LocalDate.of(2008, 10, 2));
    }

    @ParameterizedTest(name = "zona del reloj: {0}")
    @ValueSource(strings = {"UTC", "Asia/Tokyo", "America/New_York", "Europe/Madrid"})
    void registrarCliente_cumpleDieciochoEnMadridDentroDeUnDia_seRechazaSinImportarLaZonaDelReloj( // CP-REG-37
            String zonaDelReloj) {
        // Given: en Madrid es 2026-10-02 y el usuario cumple 18 el 2026-10-03
        Clock reloj = Clock.fixed(Instant.parse("2026-10-01T22:30:00Z"), ZoneId.of(zonaDelReloj));
        ServicioRegistro servicioConReloj = crearServicio(reloj);
        SolicitudRegistroClienteDTO solicitud = solicitudNacidoEl(LocalDate.of(2008, 10, 3));

        // When
        DatosRegistroInvalidosException excepcion = registrarEsperandoRechazo(servicioConReloj, solicitud);

        // Then
        assertThat(excepcion.getErrores().get("fechaNacimiento")).contains(CodigoError.MENOR_DE_EDAD);
        verificarQueNoSeGuardoNingunUsuario();
    }

    // CP-REG-36 (formato de fecha incorrecto enviado al endpoint) se verifica en ControladorAuthTest,
    // porque el error se produce al deserializar el JSON, antes de llegar al servicio.
}