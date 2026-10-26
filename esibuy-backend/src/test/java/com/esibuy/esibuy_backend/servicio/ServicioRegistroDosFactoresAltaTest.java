package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import com.esibuy.esibuy_backend.dto.RespuestaRegistroDosFactoresDTO;
import com.esibuy.esibuy_backend.dto.SolicitudRegistroClienteDTO;
import com.esibuy.esibuy_backend.dto.SolicitudRegistroVendedorDTO;
import com.esibuy.esibuy_backend.dto.TipoCuenta;
import com.esibuy.esibuy_backend.excepcion.CodigoError;
import com.esibuy.esibuy_backend.excepcion.DatosRegistroInvalidosException;
import com.esibuy.esibuy_backend.excepcion.RegistroNoCompletadoException;
import com.esibuy.esibuy_backend.excepcion.ServicioNoDisponibleException;
import com.esibuy.esibuy_backend.modelo.DosFactores;
import com.esibuy.esibuy_backend.modelo.EstadoUsuario;
import com.esibuy.esibuy_backend.modelo.Rol;
import com.esibuy.esibuy_backend.modelo.Usuario;
import com.esibuy.esibuy_backend.seguridad.PendienteRegistroDosFactores;
import com.esibuy.esibuy_backend.util.CapturadorLogs;
import com.esibuy.esibuy_backend.util.ConstructorSolicitudCliente;
import com.esibuy.esibuy_backend.util.ConstructorSolicitudVendedor;

/**
 * Registro de clientes y vendedores con el 2FA ya configurado (HdU 2FA en el registro, fase B). Casos: CP-2FA-457 a CP-2FA-475.
 *
 * Son las sobrecargas registrarCliente/registrarVendedor(solicitud, pendiente, contexto) de ServicioRegistro, con AltaDosFactores
 * y la auditoria mockeados (AltaDosFactoresTest prueba el codigo y el 2FA; aqui se prueba el orden y el reparto de trabajo).
 * Reglas que fijan:
 *  - Orden: (1) formato de todos los campos, incluido que haya codigo ("codigoDosFactores" OBLIGATORIO), todos los errores a la vez;
 *    (2) que haya un secreto pendiente vigente PARA ESE CORREO (si no, ACTIVACION_NO_VIGENTE, sin tocar DNS ni la base de
 *    datos); (3) las comprobaciones externas de siempre (dominio, nombre comercial, categoria); (4) el codigo; (5) solo entonces
 *    se mira si el correo ya esta registrado. Asi un codigo incorrecto nunca revela si un correo esta registrado, y un error
 *    corregible (dominio, nombre comercial) no gasta el intento del codigo.
 *  - Cuenta nueva: id asignado antes de guardar (el secreto del 2FA se vuelve a cifrar con ese id), estado DESACTIVADO como
 *    siempre, 2FA activo y guardado de una vez con "insert": nunca existe una cuenta sin 2FA a medio crear.
 *  - Respuesta: los datos de siempre mas los codigos de recuperacion en claro, una sola vez. El correo repetido sigue siendo el
 *    error generico (RegistroNoCompletadoException), tambien si ganan una carrera.
 *  - Auditoria: cuenta creada con 2FA; y los rechazos por el 2FA (codigo incorrecto, no vigente) con su motivo. Nada sensible en logs.
 */
class ServicioRegistroDosFactoresAltaTest extends ServicioRegistroBaseTest {

    private static final String EMAIL = ConstructorSolicitudCliente.EMAIL_POR_DEFECTO;
    private static final String CODIGO = ConstructorSolicitudCliente.CODIGO_DOS_FACTORES_POR_DEFECTO;
    private static final String CAMPO = "codigoDosFactores";
    private static final ContextoPeticion CONTEXTO = new ContextoPeticion("203.0.113.7", "JUnit");
    private static final PendienteRegistroDosFactores PENDIENTE =
            new PendienteRegistroDosFactores(EMAIL, "1:" + "A".repeat(64), Instant.parse("2026-10-01T09:59:00Z"), 0);
    private static final AltaDosFactores.Verificado VERIFICADO = new AltaDosFactores.Verificado(new byte[20], 7L);
    private static final List<String> CODIGOS = List.of("ABCD-EFGH-IJKL-MNOP", "QRST-UVWX-YZ23-4567");
    private static final DosFactores DOS_FACTORES = DosFactores.activo("1:" + "B".repeat(64),
            Instant.parse("2026-10-01T10:00:00Z"), 7L, List.of(DosFactores.CodigoRecuperacion.nuevo("c".repeat(64))));
    private static final AltaDosFactores.DosFactoresNuevos NUEVOS =
            new AltaDosFactores.DosFactoresNuevos(DOS_FACTORES, CODIGOS);

    @BeforeEach
    void prepararElAlta() {
        lenient().when(altaDosFactores.verificar(PENDIENTE, CODIGO)).thenReturn(VERIFICADO);
        lenient().when(altaDosFactores.crear(eq(VERIFICADO), anyString())).thenReturn(NUEVOS);
    }

    private RespuestaRegistroDosFactoresDTO registrarCliente() {
        return servicio.registrarCliente(ConstructorSolicitudCliente.unaSolicitudValida().construir(), PENDIENTE, CONTEXTO);
    }

    private Usuario usuarioInsertado() {
        verify(repositorioUsuario).insert(usuarioCaptor.capture());
        return usuarioCaptor.getValue();
    }

    private static void esperaErrores(DatosRegistroInvalidosException e, Map<String, Set<CodigoError>> esperados) {
        assertThat(e.getErrores()).isEqualTo(esperados);
    }

    private void verificarQueNoSeCreoNada() {
        verify(repositorioUsuario, never()).insert(any(Usuario.class));
        verify(repositorioUsuario, never()).save(any(Usuario.class));
        verify(altaDosFactores, never()).crear(any(), anyString());
    }

    // ------------------------------------------------------------------ camino feliz

    @Test
    void registrarCliente_creaLaCuentaConElDosFactoresActivoYDevuelveLosCodigosDeRecuperacion() { // CP-2FA-457
        RespuestaRegistroDosFactoresDTO respuesta = registrarCliente();

        Usuario usuario = usuarioInsertado();
        assertThat(usuario.getId()).matches("^[0-9a-f]{24}$");
        assertThat(usuario.getEmail()).isEqualTo(EMAIL);
        assertThat(usuario.getPasswordHash()).isEqualTo(HASH_SIMULADO);
        assertThat(usuario.getRoles()).containsExactly(Rol.CLIENTE);
        assertThat(usuario.getEstado()).isEqualTo(EstadoUsuario.DESACTIVADO);
        assertThat(usuario.tieneDosFactoresActivo()).isTrue();
        assertThat(usuario.getDosFactores()).isSameAs(DOS_FACTORES);
        assertThat(respuesta.id()).isEqualTo(usuario.getId());
        assertThat(respuesta.email()).isEqualTo(EMAIL);
        assertThat(respuesta.nombre()).isEqualTo("Ana");
        assertThat(respuesta.mensaje()).isEqualTo(ServicioRegistro.MENSAJE_REGISTRO_CORRECTO);
        assertThat(respuesta.codigosRecuperacion()).containsExactlyElementsOf(CODIGOS);
        // Una sola operacion: la cuenta nace con su 2FA, no se crea primero y se completa despues
        verify(repositorioUsuario, never()).save(any(Usuario.class));
    }

    @Test
    void registrarCliente_conTipoPremium_creaLaCuentaPremium() { // CP-2FA-458
        servicio.registrarCliente(ConstructorSolicitudCliente.unaSolicitudValida().conTipoCuenta(TipoCuenta.PREMIUM).construir(),
                PENDIENTE, CONTEXTO);

        assertThat(usuarioInsertado().getRoles()).containsExactly(Rol.PREMIUM);
    }

    @Test
    void registrarVendedor_creaLaCuentaConElDosFactoresActivoYDevuelveLosCodigosDeRecuperacion() { // CP-2FA-459
        SolicitudRegistroVendedorDTO solicitud = ConstructorSolicitudVendedor.unaSolicitudValida().construir();
        PendienteRegistroDosFactores deVendedor = new PendienteRegistroDosFactores(ConstructorSolicitudVendedor.EMAIL_POR_DEFECTO,
                PENDIENTE.secretoCifrado(), PENDIENTE.creadoEn(), 0);
        when(altaDosFactores.verificar(deVendedor, CODIGO)).thenReturn(VERIFICADO);

        RespuestaRegistroDosFactoresDTO respuesta = servicio.registrarVendedor(solicitud, deVendedor, CONTEXTO);

        Usuario usuario = usuarioInsertado();
        assertThat(usuario.getRoles()).containsExactly(Rol.VENDEDOR);
        assertThat(usuario.getEstado()).isEqualTo(EstadoUsuario.DESACTIVADO);
        assertThat(usuario.tieneDosFactoresActivo()).isTrue();
        assertThat(usuario.getPerfil().getNombreComercial()).isEqualTo(solicitud.nombreComercial());
        assertThat(respuesta.id()).isEqualTo(usuario.getId());
        assertThat(respuesta.codigosRecuperacion()).containsExactlyElementsOf(CODIGOS);
    }

    @Test
    void registrar_elSecretoSeVuelveACifrarConElIdDeLaCuentaQueSeGuarda() { // CP-2FA-460
        registrarCliente();

        ArgumentCaptor<String> id = ArgumentCaptor.forClass(String.class);
        verify(altaDosFactores).crear(eq(VERIFICADO), id.capture());
        assertThat(id.getValue()).isEqualTo(usuarioInsertado().getId());
    }

    @Test
    void registrar_elCodigoSeNormalizaYElCorreoSeComparaNormalizado() { // CP-2FA-461
        SolicitudRegistroClienteDTO solicitud = ConstructorSolicitudCliente.unaSolicitudValida()
                .conEmail("  Ana.Garcia@EJEMPLO.es ").conCodigoDosFactores(" " + CODIGO + " ").construir();

        servicio.registrarCliente(solicitud, PENDIENTE, CONTEXTO);

        verify(altaDosFactores).verificar(PENDIENTE, CODIGO);
        assertThat(usuarioInsertado().getEmail()).isEqualTo(EMAIL);
    }

    @Test
    void registrar_auditaLaCuentaCreadaConElDosFactores() { // CP-2FA-462
        RespuestaRegistroDosFactoresDTO respuesta = registrarCliente();

        verify(auditoria).registrarRegistroDosFactoresCompletado(CONTEXTO, EMAIL, respuesta.id());
    }

    // ------------------------------------------------------------------ 1. formato

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "   "})
    void registrar_sinCodigo_devuelveObligatorioSinTocarNada(String codigo) { // CP-2FA-463
        SolicitudRegistroClienteDTO solicitud = ConstructorSolicitudCliente.unaSolicitudValida()
                .conCodigoDosFactores(codigo).construir();

        DatosRegistroInvalidosException e = assertThrows(DatosRegistroInvalidosException.class,
                () -> servicio.registrarCliente(solicitud, PENDIENTE, CONTEXTO));

        esperaErrores(e, Map.of(CAMPO, Set.of(CodigoError.OBLIGATORIO)));
        verifyNoInteractions(validadorDominioEmail, altaDosFactores);
        verificarQueNoSeCreoNada();
    }

    @Test
    void registrar_sinCodigoYConOtrosCamposInvalidos_devuelveTodosLosErroresAlMismoTiempo() { // CP-2FA-464
        SolicitudRegistroClienteDTO solicitud = ConstructorSolicitudCliente.unaSolicitudValida()
                .conNombre("").conCodigoDosFactores(null).construir();

        DatosRegistroInvalidosException e = assertThrows(DatosRegistroInvalidosException.class,
                () -> servicio.registrarCliente(solicitud, PENDIENTE, CONTEXTO));

        esperaErrores(e, Map.of("nombre", Set.of(CodigoError.OBLIGATORIO), CAMPO, Set.of(CodigoError.OBLIGATORIO)));
        verifyNoInteractions(altaDosFactores);
    }

    @Test
    void registrar_conErroresDeFormato_noAnadeActivacionNoVigenteAunqueNoHayaPendiente() { // CP-2FA-465
        SolicitudRegistroClienteDTO solicitud = ConstructorSolicitudCliente.unaSolicitudValida()
                .conEmail("no-es-un-correo").construir();

        DatosRegistroInvalidosException e = assertThrows(DatosRegistroInvalidosException.class,
                () -> servicio.registrarCliente(solicitud, null, CONTEXTO));

        assertThat(e.getErrores()).containsOnlyKeys("email");
        verify(auditoria, never()).registrarRegistroDosFactoresFallido(any(), any(), any());
    }

    // ------------------------------------------------------------------ 2. secreto pendiente

    @Test
    void registrar_sinSecretoPendiente_devuelveActivacionNoVigenteSinTocarDnsNiBaseDeDatos() { // CP-2FA-466
        DatosRegistroInvalidosException e = assertThrows(DatosRegistroInvalidosException.class,
                () -> servicio.registrarCliente(ConstructorSolicitudCliente.unaSolicitudValida().construir(), null, CONTEXTO));

        esperaErrores(e, Map.of(CAMPO, Set.of(CodigoError.ACTIVACION_NO_VIGENTE)));
        verifyNoInteractions(validadorDominioEmail, altaDosFactores);
        verify(repositorioUsuario, never()).existePorEmail(anyString());
        verificarQueNoSeCreoNada();
        verify(auditoria).registrarRegistroDosFactoresFallido(CONTEXTO, EMAIL, MotivoFalloDosFactores.ACTIVACION_NO_VIGENTE);
    }

    @Test
    void registrar_conUnSecretoPendienteDeOtroCorreo_devuelveActivacionNoVigente() { // CP-2FA-467
        PendienteRegistroDosFactores deOtro = new PendienteRegistroDosFactores("otra@ejemplo.es", PENDIENTE.secretoCifrado(),
                PENDIENTE.creadoEn(), 0);

        DatosRegistroInvalidosException e = assertThrows(DatosRegistroInvalidosException.class,
                () -> servicio.registrarCliente(ConstructorSolicitudCliente.unaSolicitudValida().construir(), deOtro, CONTEXTO));

        esperaErrores(e, Map.of(CAMPO, Set.of(CodigoError.ACTIVACION_NO_VIGENTE)));
        verifyNoInteractions(validadorDominioEmail, altaDosFactores);
        verificarQueNoSeCreoNada();
        verify(auditoria).registrarRegistroDosFactoresFallido(CONTEXTO, EMAIL, MotivoFalloDosFactores.ACTIVACION_NO_VIGENTE);
    }

    // ------------------------------------------------------------------ 3. comprobaciones externas

    @Test
    void registrar_conUnDominioInexistente_devuelveElErrorDelCorreoSinComprobarElCodigo() { // CP-2FA-468
        when(validadorDominioEmail.tieneDominioValido(EMAIL)).thenReturn(false);

        DatosRegistroInvalidosException e = assertThrows(DatosRegistroInvalidosException.class, this::registrarCliente);

        esperaErrores(e, Map.of("email", Set.of(CodigoError.DOMINIO_EMAIL_INEXISTENTE)));
        // Es un error corregible: el codigo ni se mira ni cuenta como fallo
        verifyNoInteractions(altaDosFactores);
        verify(auditoria, never()).registrarRegistroDosFactoresFallido(any(), any(), any());
        verificarQueNoSeCreoNada();
    }

    @Test
    void registrarVendedor_conNombreComercialDuplicado_devuelveElErrorSinComprobarElCodigo() { // CP-2FA-469
        PendienteRegistroDosFactores deVendedor = new PendienteRegistroDosFactores(ConstructorSolicitudVendedor.EMAIL_POR_DEFECTO,
                PENDIENTE.secretoCifrado(), PENDIENTE.creadoEn(), 0);
        when(repositorioUsuario.existePorNombreComercial(anyString())).thenReturn(true);

        DatosRegistroInvalidosException e = assertThrows(DatosRegistroInvalidosException.class,
                () -> servicio.registrarVendedor(ConstructorSolicitudVendedor.unaSolicitudValida().construir(), deVendedor, CONTEXTO));

        esperaErrores(e, Map.of("nombreComercial", Set.of(CodigoError.NOMBRE_COMERCIAL_DUPLICADO)));
        verifyNoInteractions(altaDosFactores);
    }

    @Test
    void registrar_siFallaLaComprobacionDelDominio_esUn503YNoSeCompruebaElCodigo() { // CP-2FA-470
        when(validadorDominioEmail.tieneDominioValido(anyString())).thenThrow(new IllegalStateException("DNS caido"));

        assertThrows(ServicioNoDisponibleException.class, this::registrarCliente);

        verifyNoInteractions(altaDosFactores);
        verificarQueNoSeCreoNada();
    }

    // ------------------------------------------------------------------ 4. codigo, 5. unicidad

    @Test
    void registrar_conUnCodigoIncorrecto_propagaElErrorYAudita() { // CP-2FA-471
        DatosRegistroInvalidosException deAlta = new DatosRegistroInvalidosException(
                Map.of(CAMPO, Set.of(CodigoError.CODIGO_INCORRECTO)));
        when(altaDosFactores.verificar(PENDIENTE, CODIGO)).thenThrow(deAlta);
        // Aunque el correo ya estuviera registrado, un codigo incorrecto no debe revelarlo: ni se consulta
        lenient().when(repositorioUsuario.existePorEmail(EMAIL)).thenReturn(true);

        DatosRegistroInvalidosException e = assertThrows(DatosRegistroInvalidosException.class, this::registrarCliente);

        esperaErrores(e, Map.of(CAMPO, Set.of(CodigoError.CODIGO_INCORRECTO)));
        verify(repositorioUsuario, never()).existePorEmail(anyString());
        verify(auditoria).registrarRegistroDosFactoresFallido(CONTEXTO, EMAIL, MotivoFalloDosFactores.CODIGO_INCORRECTO);
        verificarQueNoSeCreoNada();
    }

    @Test
    void registrar_conElCorreoYaRegistrado_esElErrorGenericoYNoCreaNada() { // CP-2FA-472
        when(repositorioUsuario.existePorEmail(EMAIL)).thenReturn(true);

        assertThrows(RegistroNoCompletadoException.class, this::registrarCliente);

        verificarQueNoSeCreoNada();
        verify(auditoria, never()).registrarRegistroDosFactoresCompletado(any(), any(), any());
    }

    @Test
    void registrar_siGanaUnaCarreraConElMismoCorreo_esElErrorGenerico() { // CP-2FA-473
        when(repositorioUsuario.insert(any(Usuario.class))).thenThrow(new DuplicateKeyException("E11000 " + EMAIL));

        RegistroNoCompletadoException e = assertThrows(RegistroNoCompletadoException.class, this::registrarCliente);

        assertThat(e.getMessage()).doesNotContain(EMAIL);
        assertThat(e.getCause()).isNull();
        verify(auditoria, never()).registrarRegistroDosFactoresCompletado(any(), any(), any());
    }

    @Test
    void registrar_siFallaElAlta_nadaSeGuarda() { // CP-2FA-474
        when(altaDosFactores.verificar(PENDIENTE, CODIGO)).thenThrow(new ServicioNoDisponibleException(null));

        assertThrows(ServicioNoDisponibleException.class, this::registrarCliente);

        verificarQueNoSeCreoNada();
    }

    // ------------------------------------------------------------------ logs

    @Test
    void registrar_ningunLogDeLaAplicacionContieneElCodigoLosCodigosDeRecuperacionLaContrasenaNiElCorreo() { // CP-2FA-475
        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            registrarCliente();

            String texto = String.join("\n", logs.eventos().stream()
                    .filter(evento -> evento.getLoggerName().startsWith("com.esibuy"))
                    .map(evento -> evento.getFormattedMessage()).toList());
            assertThat(texto).doesNotContain(CODIGO, EMAIL, ConstructorSolicitudCliente.CONTRASENA_VALIDA);
            CODIGOS.forEach(codigo -> assertThat(texto).doesNotContain(codigo));
        }
    }
}
