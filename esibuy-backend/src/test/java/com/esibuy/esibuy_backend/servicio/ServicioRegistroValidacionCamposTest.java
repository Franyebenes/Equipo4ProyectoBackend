package com.esibuy.esibuy_backend.servicio;

import com.esibuy.esibuy_backend.dto.SolicitudRegistroClienteDTO;
import com.esibuy.esibuy_backend.dto.SolicitudRegistroVendedorDTO;
import com.esibuy.esibuy_backend.excepcion.CodigoError;
import com.esibuy.esibuy_backend.excepcion.DatosRegistroInvalidosException;
import com.esibuy.esibuy_backend.excepcion.ServicioNoDisponibleException;
import com.esibuy.esibuy_backend.modelo.Usuario;
import com.esibuy.esibuy_backend.util.ConstructorSolicitudCliente;
import com.esibuy.esibuy_backend.util.ConstructorSolicitudVendedor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static com.esibuy.esibuy_backend.servicio.ServicioRegistro.LONGITUD_MAXIMA_CAMPO_TEXTO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Validacion de campos obligatorios, formato, contrasena y entradas maliciosas.
 */
class ServicioRegistroValidacionCamposTest extends ServicioRegistroBaseTest {

    // ------------------------------------------------------------------ utilidades

    private static Arguments caso(String campo, UnaryOperator<ConstructorSolicitudCliente> modificador) {
        return arguments(campo, modificador);
    }

    private DatosRegistroInvalidosException registrarClienteEsperandoErrores(SolicitudRegistroClienteDTO solicitud) {
        return assertThrows(DatosRegistroInvalidosException.class, () -> servicio.registrarCliente(solicitud));
    }

    private DatosRegistroInvalidosException registrarVendedorEsperandoErrores(SolicitudRegistroVendedorDTO solicitud) {
        return assertThrows(DatosRegistroInvalidosException.class, () -> servicio.registrarVendedor(solicitud));
    }

    // ------------------------------------------------------------------ CP-REG-21

    static Stream<Arguments> camposObligatoriosAusentes() {
        List<Arguments> casos = new ArrayList<>();
        for (String valor : new String[] {null, "", "   "}) {
            casos.add(caso("nombre", c -> c.conNombre(valor)));
            casos.add(caso("apellidos", c -> c.conApellidos(valor)));
            casos.add(caso("dni", c -> c.conDni(valor)));
            casos.add(caso("email", c -> c.conEmail(valor)));
            casos.add(caso("contrasena", c -> c.conContrasena(valor)));
        }
        casos.add(caso("fechaNacimiento", c -> c.conFechaNacimiento(null)));
        return casos.stream();
    }

    @ParameterizedTest(name = "campo obligatorio \"{0}\" ausente")
    @MethodSource("camposObligatoriosAusentes")
    void registrarCliente_campoObligatorioAusente_rechazaConErrorEnEseCampoYNoGuarda( // CP-REG-21
            String campo, UnaryOperator<ConstructorSolicitudCliente> modificador) {
        // Given
        SolicitudRegistroClienteDTO solicitud =
                modificador.apply(ConstructorSolicitudCliente.unaSolicitudValida()).construir();

        // When
        DatosRegistroInvalidosException excepcion = registrarClienteEsperandoErrores(solicitud);

        // Then
        assertThat(excepcion.getErrores()).containsKey(campo);
        assertThat(excepcion.getErrores().get(campo)).contains(CodigoError.OBLIGATORIO);
        verificarQueNoSeGuardoNingunUsuario();
    }

    // ------------------------------------------------------------------ CP-REG-22

    @Test
    void registrarCliente_variosCamposInvalidos_devuelveTodosLosErroresALaVez() { // CP-REG-22
        // Given
        SolicitudRegistroClienteDTO solicitud = ConstructorSolicitudCliente.unaSolicitudValida()
                .conNombre("")
                .conDni("   ")
                .conEmail("esto-no-es-un-email")
                .conTelefono("123")
                .conFechaNacimiento(LocalDate.of(2030, 1, 1))
                .construir();

        // When
        DatosRegistroInvalidosException excepcion = registrarClienteEsperandoErrores(solicitud);

        // Then
        assertThat(excepcion.getErrores()).containsKeys("nombre", "dni", "email", "telefono", "fechaNacimiento");
        verificarQueNoSeGuardoNingunUsuario();
    }

    // ------------------------------------------------------------------ CP-REG-23 y 24 y 25 (email)

    @ParameterizedTest(name = "email invalido: \"{0}\"")
    @ValueSource(strings = {"sin-arroba", "a@", "a@@b.com", "a b@c.com", "@dominio.es", "usuario@"})
    void registrarCliente_emailConFormatoInvalido_rechazaSinConsultarDominioNiDuplicados(String emailInvalido) { // CP-REG-23
        // Given
        SolicitudRegistroClienteDTO solicitud = ConstructorSolicitudCliente.unaSolicitudValida()
                .conEmail(emailInvalido).construir();

        // When
        DatosRegistroInvalidosException excepcion = registrarClienteEsperandoErrores(solicitud);

        // Then
        assertThat(excepcion.getErrores().get("email")).contains(CodigoError.FORMATO_INVALIDO);
        verifyNoInteractions(validadorDominioEmail);
        verify(repositorioUsuario, never()).existePorEmail(anyString());
        verificarQueNoSeGuardoNingunUsuario();
    }

    @Test
    void registrarCliente_dominioDelEmailInexistente_rechazaConErrorEspecifico() { // CP-REG-24
        // Given
        when(validadorDominioEmail.tieneDominioValido(ConstructorSolicitudCliente.EMAIL_POR_DEFECTO))
                .thenReturn(false);
        SolicitudRegistroClienteDTO solicitud = ConstructorSolicitudCliente.unaSolicitudValida().construir();

        // When
        DatosRegistroInvalidosException excepcion = registrarClienteEsperandoErrores(solicitud);

        // Then
        assertThat(excepcion.getErrores().get("email")).contains(CodigoError.DOMINIO_EMAIL_INEXISTENTE);
        verificarQueNoSeGuardoNingunUsuario();
    }

    @Test
    void registrarCliente_fallaLaComprobacionDeDominio_lanzaErrorControladoYNoRegistra() { // CP-REG-25
        // Given: el servicio externo de dominios no responde
        doThrow(new IllegalStateException("timeout al consultar DNS"))
                .when(validadorDominioEmail).tieneDominioValido(anyString());
        SolicitudRegistroClienteDTO solicitud = ConstructorSolicitudCliente.unaSolicitudValida().construir();

        // When
        ServicioNoDisponibleException excepcion =
                assertThrows(ServicioNoDisponibleException.class, () -> servicio.registrarCliente(solicitud));

        // Then
        assertThat(excepcion.getMessage()).doesNotContain("DNS");
        verificarQueNoSeGuardoNingunUsuario();
    }

    // ------------------------------------------------------------------ CP-REG-26 y 27 (telefono)

    @ParameterizedTest(name = "telefono invalido: \"{0}\"")
    @ValueSource(strings = {"12345678", "6123456789", "61234567a", "+34612345678", "612 345 678", "612-345-678"})
    void registrarCliente_telefonoQueNoSonNueveDigitos_rechazaConErrorEnTelefono(String telefono) { // CP-REG-26
        // Given
        SolicitudRegistroClienteDTO solicitud = ConstructorSolicitudCliente.unaSolicitudValida()
                .conTelefono(telefono).construir();

        // When
        DatosRegistroInvalidosException excepcion = registrarClienteEsperandoErrores(solicitud);

        // Then
        assertThat(excepcion.getErrores().get("telefono")).contains(CodigoError.TELEFONO_INVALIDO);
        verificarQueNoSeGuardoNingunUsuario();
    }

    @ParameterizedTest(name = "telefono valido: \"{0}\"")
    @ValueSource(strings = {"612345678", "912345678"})
    void registrarCliente_telefonoDeNueveDigitosExactos_seAcepta(String telefono) { // CP-REG-26
        // Given
        SolicitudRegistroClienteDTO solicitud = ConstructorSolicitudCliente.unaSolicitudValida()
                .conTelefono(telefono).construir();

        // When
        servicio.registrarCliente(solicitud);

        // Then
        assertThat(usuarioGuardado().getPerfil().getTelefono()).isEqualTo(telefono);
    }

    @Test
    void registrarCliente_telefonoVacio_seTrataComoAusenteYNoFallaContraElEsquema() { // CP-REG-27
        // Given
        SolicitudRegistroClienteDTO solicitud = ConstructorSolicitudCliente.unaSolicitudValida()
                .conTelefono("").construir();

        // When
        servicio.registrarCliente(solicitud);

        // Then
        assertThat(usuarioGuardado().getPerfil().getTelefono()).isNull();
    }

    // ------------------------------------------------------------------ CP-REG-28 (avatar)

    @ParameterizedTest(name = "avatar fuera del catalogo: \"{0}\"")
    @ValueSource(strings = {
            "https://evil.example.com/foto.png",
            "javascript:alert(1)",
            "../../etc/passwd",
            "avatar-que-no-existe"})
    void registrarCliente_avatarFueraDelCatalogo_rechazaConAvatarNoPermitido(String avatar) { // CP-REG-28
        // Given
        SolicitudRegistroClienteDTO solicitud = ConstructorSolicitudCliente.unaSolicitudValida()
                .conAvatar(avatar).construir();

        // When
        DatosRegistroInvalidosException excepcion = registrarClienteEsperandoErrores(solicitud);

        // Then
        assertThat(excepcion.getErrores().get("avatar")).contains(CodigoError.AVATAR_NO_PERMITIDO);
        verificarQueNoSeGuardoNingunUsuario();
    }

    // ------------------------------------------------------------------ CP-REG-29 (longitud)

    static Stream<Arguments> camposDemasiadoLargos() {
        String excesivo = "a".repeat(LONGITUD_MAXIMA_CAMPO_TEXTO + 1);
        return Stream.of(
                caso("nombre", c -> c.conNombre(excesivo)),
                caso("apellidos", c -> c.conApellidos(excesivo)),
                caso("dni", c -> c.conDni("1".repeat(LONGITUD_MAXIMA_CAMPO_TEXTO + 1))));
    }

    @ParameterizedTest(name = "campo demasiado largo: \"{0}\"")
    @MethodSource("camposDemasiadoLargos")
    void registrarCliente_campoConLongitudSuperiorAlMaximo_rechazaConLongitudExcesiva( // CP-REG-29
            String campo, UnaryOperator<ConstructorSolicitudCliente> modificador) {
        // Given
        SolicitudRegistroClienteDTO solicitud =
                modificador.apply(ConstructorSolicitudCliente.unaSolicitudValida()).construir();

        // When
        DatosRegistroInvalidosException excepcion = registrarClienteEsperandoErrores(solicitud);

        // Then
        assertThat(excepcion.getErrores().get(campo)).contains(CodigoError.LONGITUD_EXCESIVA);
        verificarQueNoSeGuardoNingunUsuario();
    }

    @Test
    void registrarCliente_nombreConLongitudMaximaExacta_seAcepta() { // CP-REG-29
        // Given
        String nombreMaximo = "a".repeat(LONGITUD_MAXIMA_CAMPO_TEXTO);
        SolicitudRegistroClienteDTO solicitud = ConstructorSolicitudCliente.unaSolicitudValida()
                .conNombre(nombreMaximo).construir();

        // When
        servicio.registrarCliente(solicitud);

        // Then
        assertThat(usuarioGuardado().getPerfil().getNombre()).isEqualTo(nombreMaximo);
    }

    // ------------------------------------------------------------------ CP-REG-46 (vendedor)

    @ParameterizedTest(name = "categoria principal ausente: \"{0}\"")
    @ValueSource(strings = {"", "   "})
    void registrarVendedor_sinCategoriaPrincipal_rechazaConErrorEnLaCategoria(String valorVacio) { // CP-REG-46
        // Given
        SolicitudRegistroVendedorDTO solicitud = ConstructorSolicitudVendedor.unaSolicitudValida()
                .conCategoriaPrincipalId(valorVacio).construir();

        // When
        DatosRegistroInvalidosException excepcion = registrarVendedorEsperandoErrores(solicitud);

        // Then
        assertThat(excepcion.getErrores().get("categoriaPrincipalId")).contains(CodigoError.OBLIGATORIO);
        verificarQueNoSeGuardoNingunUsuario();
    }

    @Test
    void registrarVendedor_categoriaPrincipalNula_rechazaConErrorEnLaCategoria() { // CP-REG-46
        // Given
        SolicitudRegistroVendedorDTO solicitud = ConstructorSolicitudVendedor.unaSolicitudValida()
                .conCategoriaPrincipalId(null).construir();

        // When
        DatosRegistroInvalidosException excepcion = registrarVendedorEsperandoErrores(solicitud);

        // Then
        assertThat(excepcion.getErrores().get("categoriaPrincipalId")).contains(CodigoError.OBLIGATORIO);
    }

    @ParameterizedTest(name = "nombre comercial ausente: \"{0}\"")
    @ValueSource(strings = {"", "   "})
    void registrarVendedor_sinNombreComercial_rechazaConErrorEnElNombreComercial(String valorVacio) { // CP-REG-46
        // Given
        SolicitudRegistroVendedorDTO solicitud = ConstructorSolicitudVendedor.unaSolicitudValida()
                .conNombreComercial(valorVacio).construir();

        // When
        DatosRegistroInvalidosException excepcion = registrarVendedorEsperandoErrores(solicitud);

        // Then
        assertThat(excepcion.getErrores().get("nombreComercial")).contains(CodigoError.OBLIGATORIO);
        verificarQueNoSeGuardoNingunUsuario();
    }

    @Test
    void registrarVendedor_nombreComercialNulo_rechazaConErrorEnElNombreComercial() { // CP-REG-46
        // Given
        SolicitudRegistroVendedorDTO solicitud = ConstructorSolicitudVendedor.unaSolicitudValida()
                .conNombreComercial(null).construir();

        // When
        DatosRegistroInvalidosException excepcion = registrarVendedorEsperandoErrores(solicitud);

        // Then
        assertThat(excepcion.getErrores().get("nombreComercial")).contains(CodigoError.OBLIGATORIO);
    }

    // ------------------------------------------------------------------ CP-REG-52 (normalizacion)

    @Test
    void registrarCliente_nombreConEspaciosYApellidosEnNfd_losGuardaNormalizados() { // CP-REG-52
        // Given: nombre con espacios sobrantes y apellido con la tilde descompuesta (NFD)
        String apellidosEnNfd = Normalizer.normalize("Garc\u00eda", Normalizer.Form.NFD);
        SolicitudRegistroClienteDTO solicitud = ConstructorSolicitudCliente.unaSolicitudValida()
                .conNombre("  Ana  ").conApellidos(apellidosEnNfd).construir();

        // When
        servicio.registrarCliente(solicitud);

        // Then
        Usuario usuario = usuarioGuardado();
        assertThat(usuario.getPerfil().getNombre()).isEqualTo("Ana");
        assertThat(usuario.getPerfil().getApellidos()).isEqualTo("Garc\u00eda");
    }

    // ------------------------------------------------------------------ Contrasena (CP-PWD-05/06/07/13/14)

    @Test
    void registrarCliente_repeticionDeContrasenaDistinta_rechazaSinCodificarNiGuardar() { // CP-PWD-14
        // Given
        SolicitudRegistroClienteDTO solicitud = ConstructorSolicitudCliente.unaSolicitudValida()
                .conRepeticionContrasena("Tren-Azul-Lluvia-78").construir();

        // When
        DatosRegistroInvalidosException excepcion = registrarClienteEsperandoErrores(solicitud);

        // Then
        assertThat(excepcion.getErrores().get("repetirContrasena")).contains(CodigoError.CONTRASENAS_NO_COINCIDEN);
        verifyNoInteractions(codificador);
        verificarQueNoSeGuardoNingunUsuario();
    }

    @Test
    void registrarCliente_repeticionQueSoloDifierePorMayusculas_tambienSeRechaza() { // CP-PWD-14
        // Given
        SolicitudRegistroClienteDTO solicitud = ConstructorSolicitudCliente.unaSolicitudValida()
                .conRepeticionContrasena("tren-azul-lluvia-77").construir();

        // When
        DatosRegistroInvalidosException excepcion = registrarClienteEsperandoErrores(solicitud);

        // Then
        assertThat(excepcion.getErrores().get("repetirContrasena")).contains(CodigoError.CONTRASENAS_NO_COINCIDEN);
        verificarQueNoSeGuardoNingunUsuario();
    }

    @Test
    void registrarCliente_contrasenaFiltrada_rechazaEnElCampoContrasenaSinCodificar() { // CP-PWD-05
        // Given
        when(diccionario.estaFiltrada(ConstructorSolicitudCliente.CONTRASENA_VALIDA)).thenReturn(true);
        SolicitudRegistroClienteDTO solicitud = ConstructorSolicitudCliente.unaSolicitudValida().construir();

        // When
        DatosRegistroInvalidosException excepcion = registrarClienteEsperandoErrores(solicitud);

        // Then
        assertThat(excepcion.getErrores().get("contrasena")).contains(CodigoError.CONTRASENA_FILTRADA);
        verify(codificador, never()).encode(any());
        verificarQueNoSeGuardoNingunUsuario();
    }

    @Test
    void registrarCliente_contrasenaConSuPropioNombre_rechazaPorDatosPersonales() { // CP-PWD-06
        // Given
        SolicitudRegistroClienteDTO solicitud = ConstructorSolicitudCliente.unaSolicitudValida()
                .conContrasena("Xk-Ana-Lluvia-7788").construir();

        // When
        DatosRegistroInvalidosException excepcion = registrarClienteEsperandoErrores(solicitud);

        // Then
        assertThat(excepcion.getErrores().get("contrasena")).contains(CodigoError.CONTRASENA_CON_DATOS_PERSONALES);
        verificarQueNoSeGuardoNingunUsuario();
    }

    @Test
    void registrarVendedor_contrasenaConSuNombreComercial_rechazaPorDatosPersonales() { // CP-PWD-07
        // Given
        SolicitudRegistroVendedorDTO solicitud = ConstructorSolicitudVendedor.unaSolicitudValida()
                .conContrasena("Xk-Tienda-Lluvia-7788").construir();

        // When
        DatosRegistroInvalidosException excepcion = registrarVendedorEsperandoErrores(solicitud);

        // Then
        assertThat(excepcion.getErrores().get("contrasena")).contains(CodigoError.CONTRASENA_CON_DATOS_PERSONALES);
        verificarQueNoSeGuardoNingunUsuario();
    }

    @Test
    void registrarCliente_contrasenaInvalida_losErroresNuncaIncluyenLaContrasenaIntroducida() { // CP-PWD-13
        // Given
        String contrasenaComun = "password1";
        when(diccionario.esComun(contrasenaComun)).thenReturn(true);
        SolicitudRegistroClienteDTO solicitud = ConstructorSolicitudCliente.unaSolicitudValida()
                .conContrasena(contrasenaComun).construir();

        // When
        DatosRegistroInvalidosException excepcion = registrarClienteEsperandoErrores(solicitud);

        // Then
        assertThat(excepcion.getMessage()).doesNotContain(contrasenaComun);
        assertThat(excepcion.toString()).doesNotContain(contrasenaComun);
        assertThat(excepcion.getErrores().toString()).doesNotContain(contrasenaComun);
        assertThat(excepcion.getCause()).isNull();
    }

    // ------------------------------------------------------------------ Entradas maliciosas (CP-SEG-03/04/05)

    @ParameterizedTest(name = "inyeccion NoSQL en el email: {0}")
    @ValueSource(strings = {"{\"$ne\":null}", "{\"$gt\":\"\"}", "{\"$where\":\"sleep(5000)\"}", "$ne"})
    void registrarCliente_emailConOperadoresNoSql_rechazaSinConsultarElRepositorio(String emailMalicioso) { // CP-SEG-03
        // Given
        SolicitudRegistroClienteDTO solicitud = ConstructorSolicitudCliente.unaSolicitudValida()
                .conEmail(emailMalicioso).construir();

        // When
        DatosRegistroInvalidosException excepcion = registrarClienteEsperandoErrores(solicitud);

        // Then
        assertThat(excepcion.getErrores().get("email")).contains(CodigoError.FORMATO_INVALIDO);
        verify(repositorioUsuario, never()).existePorEmail(anyString());
        verifyNoInteractions(validadorDominioEmail);
        verificarQueNoSeGuardoNingunUsuario();
    }

    @ParameterizedTest(name = "inyeccion NoSQL en \"{0}\"")
    @MethodSource("camposDeClienteConOperadoresNoSql")
    void registrarCliente_camposDeTextoConOperadoresNoSql_rechazaConFormatoInvalido( // CP-SEG-04
            String campo, UnaryOperator<ConstructorSolicitudCliente> modificador) {
        // Given
        SolicitudRegistroClienteDTO solicitud =
                modificador.apply(ConstructorSolicitudCliente.unaSolicitudValida()).construir();

        // When
        DatosRegistroInvalidosException excepcion = registrarClienteEsperandoErrores(solicitud);

        // Then
        assertThat(excepcion.getErrores().get(campo)).contains(CodigoError.FORMATO_INVALIDO);
        verificarQueNoSeGuardoNingunUsuario();
    }

    static Stream<Arguments> camposDeClienteConOperadoresNoSql() {
        return Stream.of(
                caso("nombre", c -> c.conNombre("{\"$gt\":\"\"}")),
                caso("apellidos", c -> c.conApellidos("{\"$where\":\"1==1\"}")),
                caso("dni", c -> c.conDni("{\"$ne\":null}")));
    }

    @Test
    void registrarVendedor_nombreComercialConOperadoresNoSql_rechazaSinConsultarUnicidad() { // CP-SEG-04
        // Given
        SolicitudRegistroVendedorDTO solicitud = ConstructorSolicitudVendedor.unaSolicitudValida()
                .conNombreComercial("{\"$where\":\"sleep(5000)\"}").construir();

        // When
        DatosRegistroInvalidosException excepcion = registrarVendedorEsperandoErrores(solicitud);

        // Then
        assertThat(excepcion.getErrores().get("nombreComercial")).contains(CodigoError.FORMATO_INVALIDO);
        verify(repositorioUsuario, never()).existePorNombreComercial(anyString());
        verificarQueNoSeGuardoNingunUsuario();
    }

    @ParameterizedTest(name = "XSS en \"{0}\"")
    @MethodSource("camposDeClienteConScript")
    void registrarCliente_camposDeTextoConScript_rechazaConFormatoInvalido( // CP-SEG-05
            String campo, UnaryOperator<ConstructorSolicitudCliente> modificador) {
        // Given
        SolicitudRegistroClienteDTO solicitud =
                modificador.apply(ConstructorSolicitudCliente.unaSolicitudValida()).construir();

        // When
        DatosRegistroInvalidosException excepcion = registrarClienteEsperandoErrores(solicitud);

        // Then
        assertThat(excepcion.getErrores().get(campo)).contains(CodigoError.FORMATO_INVALIDO);
        verificarQueNoSeGuardoNingunUsuario();
    }

    static Stream<Arguments> camposDeClienteConScript() {
        String script = "<script>alert(1)</script>";
        return Stream.of(
                caso("nombre", c -> c.conNombre(script)),
                caso("apellidos", c -> c.conApellidos("Garc\u00eda " + script)));
    }

    @Test
    void registrarVendedor_nombreComercialConScript_rechazaConFormatoInvalido() { // CP-SEG-05
        // Given
        SolicitudRegistroVendedorDTO solicitud = ConstructorSolicitudVendedor.unaSolicitudValida()
                .conNombreComercial("<script>alert(1)</script>").construir();

        // When
        DatosRegistroInvalidosException excepcion = registrarVendedorEsperandoErrores(solicitud);

        // Then
        assertThat(excepcion.getErrores().get("nombreComercial")).contains(CodigoError.FORMATO_INVALIDO);
        verificarQueNoSeGuardoNingunUsuario();
    }
}