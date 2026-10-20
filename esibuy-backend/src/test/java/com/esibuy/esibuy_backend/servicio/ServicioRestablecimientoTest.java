package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.text.Normalizer;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.esibuy.esibuy_backend.dto.SolicitudRestablecimientoDTO;
import com.esibuy.esibuy_backend.excepcion.CodigoError;
import com.esibuy.esibuy_backend.excepcion.DatosRestablecimientoInvalidosException;
import com.esibuy.esibuy_backend.excepcion.DemasiadosIntentosRestablecimientoException;
import com.esibuy.esibuy_backend.excepcion.ServicioNoDisponibleException;
import com.esibuy.esibuy_backend.modelo.EstadoUsuario;
import com.esibuy.esibuy_backend.modelo.PerfilUsuario;
import com.esibuy.esibuy_backend.modelo.Rol;
import com.esibuy.esibuy_backend.modelo.Usuario;
import com.esibuy.esibuy_backend.repositorio.RepositorioUsuario;
import com.esibuy.esibuy_backend.servicio.ResultadoToken.Motivo;
import com.esibuy.esibuy_backend.util.CapturadorLogs;
import com.esibuy.esibuy_backend.util.DiccionarioContrasenasFalso;
import com.esibuy.esibuy_backend.util.RelojMutable;

/**
 * Logica del restablecimiento de la contrasena con el token (HdU recuperacion de contrasena, fase 4).
 * Casos: CP-RST-01 a CP-RST-17.
 *
 * Colaboradores: repositorio, servicio de tokens, codificador, limitador y auditoria mockeados; ValidadorContrasena
 * REAL (con el diccionario falso), porque lo que se fija es que la politica de contrasenas vigente se aplica entera.
 *
 * Reglas que fijan (decididas con el equipo):
 *  - Orden: limite de IP, validar el token (sin consumirlo), cuenta ACTIVA, politica de la contrasena, y solo entonces
 *    consumir el token (operacion atomica), codificar, guardar y anular los demas tokens del usuario. Asi una contrasena
 *    rechazada no gasta el token y el usuario puede volver a intentarlo con el mismo enlace.
 *  - Un enlace que no vale (desconocido, caducado, anulado, ya usado, cuenta no activa o inexistente) da SIEMPRE el
 *    mismo error (campo token, ENLACE_INVALIDO); el motivo solo queda en la auditoria. Reutilizar un enlace ya usado
 *    es ademas una alerta de seguridad.
 *  - La contrasena incumple la politica: error en el campo contrasena con todos los codigos a la vez.
 *  - La contrasena se normaliza a NFC y NO se recorta (los espacios forman parte de la contrasena), como en el login.
 *  - Se acepta que la contrasena nueva sea igual a la actual: rechazarla permitiria a quien tenga un enlace probar
 *    contrasenas actuales una a una.
 *  - Se guarda la marca contrasenaCambiadaEn con el instante del reloj, que es la que invalida las sesiones antiguas.
 *  - Cada intento (aunque el token sea basura) cuenta para el limite de la IP.
 *  - Nunca se escribe en un log ni en un mensaje de error la contrasena ni el token.
 *
 * Terminada: ya no lleva la etiqueta pendiente-recuperacion y entra en la regresion.
 */
@ExtendWith(MockitoExtension.class)
class ServicioRestablecimientoTest {

    private static final String ID_USUARIO = "665f1c2e9b1e8a3d4c5b6a79";
    private static final String EMAIL = "ana.garcia@ejemplo.es";
    private static final String TOKEN = "Zk3-Qw9_Lm2xPa7RtYb4NcV8HdS1JfGe6UoIi0KuXyA";
    private static final String NUEVA = "Nueva-Clave-De-Prueba-42!";
    private static final String HASH_NUEVO = "hash-de-la-contrasena-nueva";
    private static final String IP = "203.0.113.7";
    private static final ContextoPeticion CONTEXTO = new ContextoPeticion(IP, "JUnit");

    @Mock
    private RepositorioUsuario repositorioUsuario;
    @Mock
    private ServicioTokensRecuperacion tokens;
    @Mock
    private PasswordEncoder codificador;
    @Mock
    private LimitadorRestablecimiento limitador;
    @Mock
    private AuditoriaSeguridad auditoria;

    private final ValidadorContrasena validador = new ValidadorContrasena(new DiccionarioContrasenasFalso());
    private final RelojMutable reloj =
            new RelojMutable(Instant.parse("2026-10-19T10:00:00Z"), ZoneId.of("Europe/Madrid"));

    private ServicioRestablecimiento servicio;

    @BeforeEach
    void prepararServicio() {
        lenient().when(limitador.registrarIntento(IP)).thenReturn(true);
        lenient().when(codificador.encode(anyString())).thenReturn(HASH_NUEVO);
        lenient().when(repositorioUsuario.save(any(Usuario.class))).thenAnswer(llamada -> llamada.getArgument(0));
        servicio = crearServicio(auditoria);
    }

    private ServicioRestablecimiento crearServicio(AuditoriaSeguridad auditoriaUsada) {
        return new ServicioRestablecimiento(repositorioUsuario, tokens, validador, codificador, limitador,
                auditoriaUsada, reloj);
    }

    private static Usuario usuario(EstadoUsuario estado) {
        Usuario usuario = new Usuario(EMAIL, "hash-antiguo", Rol.CLIENTE,
                PerfilUsuario.builder().nombre("Ana").apellidos("Garcia").build());
        ReflectionTestUtils.setField(usuario, "id", ID_USUARIO);
        ReflectionTestUtils.setField(usuario, "estado", estado);
        return usuario;
    }

    private void dadoUnTokenValidoDeUnaCuenta(EstadoUsuario estado) {
        when(tokens.validar(TOKEN)).thenReturn(new ResultadoToken(Motivo.VALIDO, ID_USUARIO));
        when(repositorioUsuario.findById(ID_USUARIO)).thenReturn(Optional.of(usuario(estado)));
    }

    private void dadoQueSePuedeConsumir() {
        when(tokens.consumir(TOKEN)).thenReturn(new ResultadoToken(Motivo.VALIDO, ID_USUARIO));
    }

    private void restablecer(String token, String contrasena) {
        servicio.restablecer(new SolicitudRestablecimientoDTO(token, contrasena), CONTEXTO);
    }

    private DatosRestablecimientoInvalidosException rechazo(String token, String contrasena) {
        return assertThrows(DatosRestablecimientoInvalidosException.class, () -> restablecer(token, contrasena));
    }

    // ------------------------------------------------------------------ CP-RST-01: camino feliz

    @Test
    void restablecer_tokenYContrasenaValidos_cambiaLaContrasenaYGastaElToken() { // CP-RST-01
        // Given
        dadoUnTokenValidoDeUnaCuenta(EstadoUsuario.ACTIVO);
        dadoQueSePuedeConsumir();

        // When
        restablecer(TOKEN, NUEVA);

        // Then: el hash nuevo y la marca de cambio, con el instante del reloj, quedan guardados
        ArgumentCaptor<Usuario> guardado = ArgumentCaptor.forClass(Usuario.class);
        verify(repositorioUsuario).save(guardado.capture());
        assertThat(guardado.getValue().getPasswordHash()).isEqualTo(HASH_NUEVO);
        assertThat(guardado.getValue().getContrasenaCambiadaEn()).isEqualTo(reloj.instant());
        verify(auditoria).registrarRestablecimientoCorrecto(CONTEXTO, EMAIL, ID_USUARIO);

        // y en este orden: se gasta el token antes de codificar y guardar, y despues se anulan los demas del usuario
        InOrder orden = inOrder(tokens, codificador, repositorioUsuario);
        orden.verify(tokens).validar(TOKEN);
        orden.verify(tokens).consumir(TOKEN);
        orden.verify(codificador).encode(NUEVA);
        orden.verify(repositorioUsuario).save(any(Usuario.class));
        orden.verify(tokens).invalidarTodos(ID_USUARIO);
    }

    // ------------------------------------------------------------------ CP-RST-02 / 03 / 04 / 05: enlace no valido

    @ParameterizedTest(name = "token {0}")
    @EnumSource(value = Motivo.class, names = { "DESCONOCIDO", "CADUCADO", "INVALIDADO" })
    void restablecer_tokenQueNoVale_dalErrorDeEnlaceSinTocarNada(Motivo motivo) { // CP-RST-02
        // Given
        when(tokens.validar(TOKEN)).thenReturn(new ResultadoToken(motivo, null));

        // When
        DatosRestablecimientoInvalidosException error = rechazo(TOKEN, NUEVA);

        // Then: error en el campo token, y no se consume, ni se busca la cuenta, ni se codifica, ni se guarda
        assertThat(error.getErrores()).containsOnlyKeys("token");
        assertThat(error.getErrores().get("token")).containsExactly(CodigoError.ENLACE_INVALIDO);
        verify(tokens, never()).consumir(anyString());
        verifyNoInteractions(repositorioUsuario, codificador);
        verify(auditoria).registrarRestablecimientoTokenInvalido(CONTEXTO, motivo);
        verify(auditoria, never()).registrarRestablecimientoTokenReutilizado(any());
    }

    @Test
    void restablecer_tokenYaUsado_dalElMismoErrorYSeAvisaDeLaReutilizacion() { // CP-RST-03
        when(tokens.validar(TOKEN)).thenReturn(new ResultadoToken(Motivo.YA_USADO, null));

        DatosRestablecimientoInvalidosException error = rechazo(TOKEN, NUEVA);

        assertThat(error.getErrores().get("token")).containsExactly(CodigoError.ENLACE_INVALIDO);
        verify(auditoria).registrarRestablecimientoTokenReutilizado(CONTEXTO);
        verify(auditoria, never()).registrarRestablecimientoTokenInvalido(any(), any());
        verifyNoInteractions(repositorioUsuario, codificador);
    }

    @ParameterizedTest(name = "token [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = { "   ", "\t\n" })
    void restablecer_tokenNuloOVacio_dalElMismoErrorSinConsultarTokens(String token) { // CP-RST-04
        DatosRestablecimientoInvalidosException error = rechazo(token, NUEVA);

        assertThat(error.getErrores()).containsOnlyKeys("token");
        assertThat(error.getErrores().get("token")).containsExactly(CodigoError.ENLACE_INVALIDO);
        verifyNoInteractions(tokens, repositorioUsuario, codificador);
        verify(auditoria).registrarRestablecimientoTokenInvalido(CONTEXTO, Motivo.DESCONOCIDO);
    }

    @Test
    void restablecer_tokenConEspaciosAlrededor_seRecortaAntesDeUsarlo() { // CP-RST-04
        // Un token pegado a mano suele traer un espacio o un salto de linea al final
        dadoUnTokenValidoDeUnaCuenta(EstadoUsuario.ACTIVO);
        dadoQueSePuedeConsumir();

        assertDoesNotThrow(() -> restablecer("  " + TOKEN + "\n", NUEVA));

        verify(tokens).consumir(TOKEN);
    }

    @Test
    void restablecer_todosLosMotivosDeRechazoDelEnlace_sonIndistinguiblesParaElCliente() { // CP-RST-05
        // Given: un token por cada motivo, y una cuenta bloqueada con token valido
        Set<DatosRestablecimientoInvalidosException> errores = new java.util.HashSet<>();
        for (Motivo motivo : Set.of(Motivo.DESCONOCIDO, Motivo.CADUCADO, Motivo.INVALIDADO, Motivo.YA_USADO)) {
            when(tokens.validar(TOKEN)).thenReturn(new ResultadoToken(motivo, null));
            errores.add(rechazo(TOKEN, NUEVA));
        }
        dadoUnTokenValidoDeUnaCuenta(EstadoUsuario.BLOQUEADO);
        errores.add(rechazo(TOKEN, NUEVA));

        // Then: mismo mensaje y mismos errores en los cinco casos
        assertThat(errores).extracting(DatosRestablecimientoInvalidosException::getMessage).containsOnly(
                "Los datos del restablecimiento de contrasena no son validos");
        assertThat(errores).extracting(DatosRestablecimientoInvalidosException::getErrores)
                .containsOnly(java.util.Map.of("token", Set.of(CodigoError.ENLACE_INVALIDO)));
    }

    // ------------------------------------------------------------------ CP-RST-06 / 07 / 08: politica de contrasenas

    static Stream<Arguments> contrasenasQueIncumplenLaPolitica() {
        return Stream.of(
                Arguments.of("demasiado corta", "Cor-1a", CodigoError.CONTRASENA_CORTA),
                Arguments.of("sin numero", "Clave-Sin-Numero-Aqui!", CodigoError.CONTRASENA_SIN_NUMERO),
                Arguments.of("sin caracter especial", "ClaveSinEspecial1234", CodigoError.CONTRASENA_SIN_CARACTER_ESPECIAL),
                Arguments.of("sin mayuscula", "clave-sin-mayuscula-42", CodigoError.CONTRASENA_SIN_MAYUSCULA),
                Arguments.of("sin minuscula", "CLAVE-SIN-MINUSCULA-42", CodigoError.CONTRASENA_SIN_MINUSCULA),
                Arguments.of("comun", "qwertyuiop12", CodigoError.CONTRASENA_COMUN),
                Arguments.of("filtrada", DiccionarioContrasenasFalso.CONTRASENA_FILTRADA,
                        CodigoError.CONTRASENA_FILTRADA),
                Arguments.of("con el nombre del usuario", "Mi-Garcia-Clave-42!", CodigoError.CONTRASENA_CON_DATOS_PERSONALES),
                Arguments.of("con la parte local del correo", "Xx-ana.garcia-Xx-42!", CodigoError.CONTRASENA_CON_DATOS_PERSONALES));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("contrasenasQueIncumplenLaPolitica")
    void restablecer_contrasenaQueIncumpleLaPolitica_dalErrorSinGastarElToken(String descripcion,
            String contrasena, CodigoError esperado) { // CP-RST-06
        // Given
        dadoUnTokenValidoDeUnaCuenta(EstadoUsuario.ACTIVO);

        // When
        DatosRestablecimientoInvalidosException error = rechazo(TOKEN, contrasena);

        // Then: error en el campo contrasena, el token sigue sin gastar y no se cambia nada
        assertThat(error.getErrores()).containsOnlyKeys("contrasena");
        assertThat(error.getErrores().get("contrasena")).contains(esperado);
        verify(tokens, never()).consumir(anyString());
        verify(tokens, never()).invalidarTodos(anyString());
        verifyNoInteractions(codificador);
        verify(repositorioUsuario, never()).save(any());
        verify(auditoria).registrarRestablecimientoContrasenaRechazada(eq(CONTEXTO), eq(EMAIL), eq(ID_USUARIO),
                org.mockito.ArgumentMatchers.argThat(motivos -> motivos.contains(esperado)));
    }

    @Test
    void restablecer_contrasenaQueIncumpleVariasReglas_devuelveTodosLosErroresAlaVez() { // CP-RST-07
        dadoUnTokenValidoDeUnaCuenta(EstadoUsuario.ACTIVO);

        DatosRestablecimientoInvalidosException error = rechazo(TOKEN, "a");

        assertThat(error.getErrores().get("contrasena")).contains(CodigoError.CONTRASENA_CORTA,
                CodigoError.CONTRASENA_SIN_NUMERO, CodigoError.CONTRASENA_SIN_CARACTER_ESPECIAL,
                CodigoError.CONTRASENA_SIN_MAYUSCULA);
    }

    @ParameterizedTest(name = "contrasena [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = { "   " })
    void restablecer_contrasenaNulaOVacia_dalObligatorio(String contrasena) { // CP-RST-08
        dadoUnTokenValidoDeUnaCuenta(EstadoUsuario.ACTIVO);

        DatosRestablecimientoInvalidosException error = rechazo(TOKEN, contrasena);

        assertThat(error.getErrores().get("contrasena")).containsExactly(CodigoError.OBLIGATORIO);
        verify(tokens, never()).consumir(anyString());
        verifyNoInteractions(codificador);
    }

    // ------------------------------------------------------------------ CP-RST-09 / 10 / 11: cuenta y carreras

    @ParameterizedTest(name = "cuenta {0}")
    @EnumSource(value = EstadoUsuario.class, names = { "DESACTIVADO", "BLOQUEADO" })
    void restablecer_cuentaQueYaNoEstaActiva_dalErrorDeEnlaceYAnulaSusTokens(EstadoUsuario estado) { // CP-RST-09
        // Given: el token se emitio cuando la cuenta estaba activa, pero ya no lo esta
        dadoUnTokenValidoDeUnaCuenta(estado);

        // When
        DatosRestablecimientoInvalidosException error = rechazo(TOKEN, NUEVA);

        // Then: mismo error que cualquier enlace malo, sin revelar el estado; no se cambia nada
        assertThat(error.getErrores().get("token")).containsExactly(CodigoError.ENLACE_INVALIDO);
        verify(tokens).invalidarTodos(ID_USUARIO);
        verify(tokens, never()).consumir(anyString());
        verifyNoInteractions(codificador);
        verify(repositorioUsuario, never()).save(any());
        verify(auditoria).registrarRestablecimientoCuentaNoApta(CONTEXTO, EMAIL, estado);
    }

    @Test
    void restablecer_laCuentaYaNoExiste_dalErrorDeEnlace() { // CP-RST-10
        // Given: token valido de un usuario que se ha borrado
        when(tokens.validar(TOKEN)).thenReturn(new ResultadoToken(Motivo.VALIDO, ID_USUARIO));
        when(repositorioUsuario.findById(ID_USUARIO)).thenReturn(Optional.empty());

        // When
        DatosRestablecimientoInvalidosException error = rechazo(TOKEN, NUEVA);

        // Then
        assertThat(error.getErrores().get("token")).containsExactly(CodigoError.ENLACE_INVALIDO);
        verify(tokens).invalidarTodos(ID_USUARIO);
        verify(auditoria).registrarRestablecimientoCuentaNoApta(CONTEXTO, null, null);
        verifyNoInteractions(codificador);
    }

    @Test
    void restablecer_otraPeticionGastaElTokenEntreLaComprobacionYElConsumo_dalErrorYAlerta() { // CP-RST-11
        // Given: dos peticiones simultaneas con el mismo enlace; esta pierde la carrera al consumirlo
        dadoUnTokenValidoDeUnaCuenta(EstadoUsuario.ACTIVO);
        when(tokens.consumir(TOKEN)).thenReturn(new ResultadoToken(Motivo.YA_USADO, null));

        // When
        DatosRestablecimientoInvalidosException error = rechazo(TOKEN, NUEVA);

        // Then: no cambia nada y es la misma respuesta de siempre
        assertThat(error.getErrores().get("token")).containsExactly(CodigoError.ENLACE_INVALIDO);
        verifyNoInteractions(codificador);
        verify(repositorioUsuario, never()).save(any());
        verify(auditoria).registrarRestablecimientoTokenReutilizado(CONTEXTO);
    }

    // ------------------------------------------------------------------ CP-RST-12 / 13: la contrasena en si

    @Test
    void restablecer_contrasenaEnFormaDescompuesta_seCodificaNormalizadaANfc() { // CP-RST-12
        // Given: la misma contrasena escrita con la n y la tilde por separado (NFD)
        String enNfc = "Clave-Ñandú-Nueva-42!";
        String enNfd = Normalizer.normalize(enNfc, Normalizer.Form.NFD);
        assertThat(enNfd).isNotEqualTo(enNfc);
        dadoUnTokenValidoDeUnaCuenta(EstadoUsuario.ACTIVO);
        dadoQueSePuedeConsumir();

        // When
        restablecer(TOKEN, enNfd);

        // Then: se codifica la forma NFC, que es la que despues compara el login
        verify(codificador).encode(enNfc);
    }

    @Test
    void restablecer_contrasenaConEspaciosAlrededor_noSeRecorta() { // CP-RST-12
        String conEspacios = "  Nueva Clave De Prueba 42!  ";
        dadoUnTokenValidoDeUnaCuenta(EstadoUsuario.ACTIVO);
        dadoQueSePuedeConsumir();

        restablecer(TOKEN, conEspacios);

        verify(codificador).encode(conEspacios);
    }

    @Test
    void restablecer_contrasenaIgualALaActual_seAceptaSinCompararlaConElHashActual() { // CP-RST-13
        dadoUnTokenValidoDeUnaCuenta(EstadoUsuario.ACTIVO);
        dadoQueSePuedeConsumir();

        assertDoesNotThrow(() -> restablecer(TOKEN, NUEVA));

        // Comparar con la actual convertiria el enlace en un oraculo para probar contrasenas una a una
        verify(codificador, never()).matches(anyString(), anyString());
    }

    // ------------------------------------------------------------------ CP-RST-14: limite de IP

    @Test
    void restablecer_ipSinIntentosDisponibles_seRechazaAntesDeHacerNadaYSeAudita() { // CP-RST-14
        when(limitador.registrarIntento(IP)).thenReturn(false);

        assertThrows(DemasiadosIntentosRestablecimientoException.class, () -> restablecer(TOKEN, NUEVA));

        verifyNoInteractions(tokens, repositorioUsuario, codificador);
        verify(auditoria).registrarRestablecimientoLimitado(CONTEXTO);
    }

    @Test
    void restablecer_cadaIntentoCuentaParaElLimiteAunqueElTokenSeaBasura() { // CP-RST-14
        when(tokens.validar("basura")).thenReturn(new ResultadoToken(Motivo.DESCONOCIDO, null));
        assertThrows(DatosRestablecimientoInvalidosException.class, () -> restablecer("basura", NUEVA));
        assertThrows(DatosRestablecimientoInvalidosException.class, () -> restablecer(null, null));

        verify(limitador, org.mockito.Mockito.times(2)).registrarIntento(IP);
    }

    // ------------------------------------------------------------------ CP-RST-15 / 16: fallos internos

    @Test
    void restablecer_fallaLaBaseDeDatosAlBuscarLaCuenta_falla503SinGastarElToken() { // CP-RST-15
        when(tokens.validar(TOKEN)).thenReturn(new ResultadoToken(Motivo.VALIDO, ID_USUARIO));
        when(repositorioUsuario.findById(ID_USUARIO)).thenThrow(new IllegalStateException("mongo caido: usuarios"));

        ServicioNoDisponibleException error = assertThrows(ServicioNoDisponibleException.class,
                () -> restablecer(TOKEN, NUEVA));

        assertThat(error.getMessage()).doesNotContain("mongo", "usuarios", TOKEN, NUEVA);
        verify(tokens, never()).consumir(anyString());
    }

    @Test
    void restablecer_fallaElCodificadorOElGuardado_falla503SinDetalles() { // CP-RST-16
        dadoUnTokenValidoDeUnaCuenta(EstadoUsuario.ACTIVO);
        dadoQueSePuedeConsumir();
        when(codificador.encode(anyString())).thenThrow(new IllegalStateException("argon2 sin memoria " + NUEVA));

        ServicioNoDisponibleException error = assertThrows(ServicioNoDisponibleException.class,
                () -> restablecer(TOKEN, NUEVA));

        assertThat(error.getMessage()).doesNotContain("argon2", NUEVA, TOKEN);
        verify(repositorioUsuario, never()).save(any());
    }

    // ------------------------------------------------------------------ CP-RST-17: logs

    @Test
    void restablecer_variosFlujos_nadaEnElLogContieneLaContrasenaNiElToken() { // CP-RST-17
        // Given: auditoria real para leer lo que de verdad se escribe en el log
        ServicioRestablecimiento conAuditoriaReal = crearServicio(new AuditoriaSeguridad());
        dadoUnTokenValidoDeUnaCuenta(EstadoUsuario.ACTIVO);
        dadoQueSePuedeConsumir();
        when(tokens.validar("token-malo")).thenReturn(new ResultadoToken(Motivo.DESCONOCIDO, null));

        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            // When: una contrasena rechazada, un token malo, y un restablecimiento correcto
            assertThrows(DatosRestablecimientoInvalidosException.class,
                    () -> conAuditoriaReal.restablecer(new SolicitudRestablecimientoDTO(TOKEN, "Mi-Garcia-Clave-42!"), CONTEXTO));
            assertThrows(DatosRestablecimientoInvalidosException.class,
                    () -> conAuditoriaReal.restablecer(new SolicitudRestablecimientoDTO("token-malo", NUEVA), CONTEXTO));
            conAuditoriaReal.restablecer(new SolicitudRestablecimientoDTO(TOKEN, NUEVA), CONTEXTO);

            // Then
            assertThat(logs.textosCompletos()).isNotEmpty().noneMatch(texto -> texto.contains(TOKEN)
                    || texto.contains(NUEVA) || texto.contains("Mi-Garcia-Clave-42!") || texto.contains(EMAIL));
        }
    }
}
