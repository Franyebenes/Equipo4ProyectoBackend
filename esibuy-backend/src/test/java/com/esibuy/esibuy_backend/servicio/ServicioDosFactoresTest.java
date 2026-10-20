package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.esibuy.esibuy_backend.dto.EstadoDosFactoresDTO;
import com.esibuy.esibuy_backend.dto.RespuestaActivacionDosFactoresDTO;
import com.esibuy.esibuy_backend.dto.RespuestaCodigosRecuperacionDTO;
import com.esibuy.esibuy_backend.excepcion.CodigoError;
import com.esibuy.esibuy_backend.excepcion.DatosDosFactoresInvalidosException;
import com.esibuy.esibuy_backend.excepcion.DemasiadosIntentosDosFactoresException;
import com.esibuy.esibuy_backend.excepcion.OperacionNoPermitidaException;
import com.esibuy.esibuy_backend.excepcion.ServicioNoDisponibleException;
import com.esibuy.esibuy_backend.excepcion.UsuarioNoEncontradoException;
import com.esibuy.esibuy_backend.modelo.DosFactores;
import com.esibuy.esibuy_backend.modelo.DosFactores.CodigoRecuperacion;
import com.esibuy.esibuy_backend.modelo.EstadoUsuario;
import com.esibuy.esibuy_backend.modelo.PerfilUsuario;
import com.esibuy.esibuy_backend.modelo.Rol;
import com.esibuy.esibuy_backend.modelo.Usuario;
import com.esibuy.esibuy_backend.repositorio.RepositorioDosFactores;
import com.esibuy.esibuy_backend.repositorio.RepositorioUsuario;
import com.esibuy.esibuy_backend.util.CapturadorLogs;
import com.esibuy.esibuy_backend.util.PropiedadesCorreoPrueba;
import com.esibuy.esibuy_backend.util.PropiedadesDosFactoresPrueba;
import com.esibuy.esibuy_backend.util.RelojMutable;

/**
 * Logica de activar, consultar, desactivar y regenerar los codigos del segundo factor (HdU 2FA, fase 2).
 * Casos: CP-2FA-179 a CP-2FA-232.
 *
 * Colaboradores: repositorios, codificador de contrasenas, limitador, notificador y auditoria mockeados; el cifrador, el
 * generador TOTP, el de codigos de recuperacion y las plantillas de correo REALES, porque lo que se fija es que el
 * secreto se cifra con el id del usuario, que el codigo se verifica de verdad y que los codigos de recuperacion que se
 * devuelven son justo los que se guardan (hasheados).
 *
 * Reglas que fijan (decididas con el equipo y en el plan):
 *  - Operar con el 2FA exige la contrasena (se repite aunque haya sesion). Desactivar y regenerar los codigos exigen
 *    ademas un codigo valido: el TOTP del movil o uno de recuperacion.
 *  - Orden: formato (un campo vacio es un 400 que no cuenta como fallo), limite de fallos, usuario, estado del 2FA,
 *    contrasena y, solo entonces, el codigo. Una contrasena mal puesta no llega a probar el codigo.
 *  - Los fallos (contrasena o codigo incorrectos) se suman al limitador y quedan en la auditoria; un TOTP ya aceptado no
 *    vale otra vez (anti-reutilizacion atomica en la base de datos) y cuenta como fallo.
 *  - Un codigo de recuperacion se consume una sola vez, tambien al desactivar o regenerar, y su uso es una alerta.
 *  - Al activar, desactivar o regenerar, se marcan las credenciales como cambiadas un milisegundo antes del instante del
 *    cambio: asi se invalidan las demas sesiones y la que se renueve justo despues (el controlador) sobrevive, porque una
 *    sesion vale solo si empezo estrictamente despues de la marca.
 *  - Cada cambio avisa por correo (en segundo plano) sin secretos ni codigos, y se audita.
 *  - El secreto pendiente caduca a los 10 minutos exactos. Los errores internos fallan cerrados (503) sin detalles.
 *  - Nunca se escribe en un log ni en un mensaje de error la contrasena, el codigo, el secreto ni los codigos de
 *    recuperacion.
 *
 * Terminada: ya no lleva la etiqueta pendiente-2fa y entra en la regresion.
 */
@ExtendWith(MockitoExtension.class)
class ServicioDosFactoresTest {

    private static final String ID = "665f1c2e9b1e8a3d4c5b6a79";
    private static final String EMAIL = "ana.garcia@ejemplo.es";
    private static final String HASH_CONTRASENA = "hash-de-la-contrasena";
    private static final String CONTRASENA = "Clave-De-Prueba-42!";
    private static final String IP = "203.0.113.7";
    private static final ContextoPeticion CONTEXTO = new ContextoPeticion(IP, "JUnit");
    private static final Instant AHORA = Instant.parse("2026-10-20T10:00:00Z");
    private static final byte[] SECRETO = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
    private static final String PEPPER = "pepper-de-pruebas";

    @Mock
    private RepositorioUsuario repositorioUsuario;
    @Mock
    private RepositorioDosFactores repositorioDosFactores;
    @Mock
    private PasswordEncoder codificador;
    @Mock
    private LimitadorDosFactores limitador;
    @Mock
    private NotificadorCorreo notificador;
    @Mock
    private AuditoriaSeguridad auditoria;

    private final RelojMutable reloj = new RelojMutable(AHORA, ZoneId.of("Europe/Madrid"));
    private final CifradorSecretos cifrador =
            new CifradorSecretos(Map.of(1, PropiedadesDosFactoresPrueba.CLAVE), 1);
    private final GeneradorTotp totp = new GeneradorTotp(reloj);
    private final GeneradorCodigosRecuperacion generadorCodigos = new GeneradorCodigosRecuperacion(PEPPER);
    private final PlantillasCorreo plantillas = new PlantillasCorreo(PropiedadesCorreoPrueba.remoto().construir());

    private ServicioDosFactores servicio;

    @BeforeEach
    void prepararServicio() {
        lenient().when(limitador.permitido(anyString(), anyString())).thenReturn(true);
        lenient().when(codificador.matches(CONTRASENA, HASH_CONTRASENA)).thenReturn(true);
        servicio = crearServicio(auditoria);
    }

    private ServicioDosFactores crearServicio(AuditoriaSeguridad auditoriaUsada) {
        return new ServicioDosFactores(repositorioUsuario, repositorioDosFactores, cifrador, totp, generadorCodigos,
                codificador, limitador, notificador, plantillas, auditoriaUsada,
                PropiedadesDosFactoresPrueba.acordadas(), reloj);
    }

    // ------------------------------------------------------------------ utilidades

    private static Usuario usuario(DosFactores dosFactores) {
        Usuario usuario = new Usuario(EMAIL, HASH_CONTRASENA, Rol.CLIENTE,
                PerfilUsuario.builder().nombre("Ana").apellidos("Garcia").build());
        ReflectionTestUtils.setField(usuario, "id", ID);
        ReflectionTestUtils.setField(usuario, "estado", EstadoUsuario.ACTIVO);
        ReflectionTestUtils.setField(usuario, "dosFactores", dosFactores);
        return usuario;
    }

    private void dadoUnUsuario(DosFactores dosFactores) {
        when(repositorioUsuario.findById(ID)).thenReturn(Optional.of(usuario(dosFactores)));
    }

    private List<String> hashesDeLosCodigos(int cantidad) {
        List<String> hashes = new ArrayList<>();
        for (int i = 0; i < cantidad; i++) {
            hashes.add(generadorCodigos.hash(generadorCodigos.nuevoCodigo()));
        }
        return hashes;
    }

    private static List<CodigoRecuperacion> codigosSinUsar(List<String> hashes) {
        return hashes.stream().map(CodigoRecuperacion::nuevo).toList();
    }

    private DosFactores pendiente(Instant creadoEn) {
        return DosFactores.pendiente(cifrador.cifrar(SECRETO, ID), creadoEn);
    }

    private DosFactores activo(long ultimoPaso, List<String> hashes) {
        return DosFactores.activo(cifrador.cifrar(SECRETO, ID), AHORA.minusSeconds(3600), ultimoPaso,
                codigosSinUsar(hashes));
    }

    private String codigoTotp(long paso) {
        return totp.codigo(SECRETO, paso);
    }

    private String codigoActual() {
        return codigoTotp(totp.pasoActual());
    }

    private static Map<String, Set<CodigoError>> erroresDe(Executable accion) {
        return assertThrows(DatosDosFactoresInvalidosException.class, accion).getErrores();
    }

    private MensajeCorreo correoEnviado() {
        ArgumentCaptor<MensajeCorreo> captor = ArgumentCaptor.forClass(MensajeCorreo.class);
        verify(notificador).notificar(captor.capture());
        return captor.getValue();
    }

    // ------------------------------------------------------------------ estado

    @Test
    void estado_sinDosFactores_noEstaActivoYNoTieneCodigos() { // CP-2FA-179
        dadoUnUsuario(null);

        EstadoDosFactoresDTO estado = servicio.estado(ID);

        assertThat(estado.activo()).isFalse();
        assertThat(estado.codigosRecuperacionDisponibles()).isZero();
    }

    @Test
    void estado_activo_cuentaSoloLosCodigosSinUsar() { // CP-2FA-180
        // Diez codigos, tres de ellos ya usados
        List<CodigoRecuperacion> codigos = codigosSinUsar(hashesDeLosCodigos(10));
        for (int i = 0; i < 3; i++) {
            ReflectionTestUtils.setField(codigos.get(i), "usado", true);
        }
        dadoUnUsuario(DosFactores.activo(cifrador.cifrar(SECRETO, ID), AHORA, 5, codigos));

        EstadoDosFactoresDTO estado = servicio.estado(ID);

        assertThat(estado.activo()).isTrue();
        assertThat(estado.codigosRecuperacionDisponibles()).isEqualTo(7);
    }

    @Test
    void estado_conUnaActivacionPendiente_noEstaActivo() { // CP-2FA-181
        dadoUnUsuario(pendiente(AHORA));

        assertThat(servicio.estado(ID).activo()).isFalse();
    }

    @Test
    void estado_usuarioInexistente_lanzaUsuarioNoEncontrado() { // CP-2FA-182
        when(repositorioUsuario.findById(ID)).thenReturn(Optional.empty());

        assertThrows(UsuarioNoEncontradoException.class, () -> servicio.estado(ID));
    }

    // ------------------------------------------------------------------ iniciar la activacion

    @Test
    void iniciarActivacion_guardaElSecretoCifradoConElIdYDevuelveLaUriYElSecreto() { // CP-2FA-183
        dadoUnUsuario(null);
        when(repositorioDosFactores.guardarActivacionPendiente(eq(ID), anyString(), eq(AHORA))).thenReturn(true);

        RespuestaActivacionDosFactoresDTO respuesta = servicio.iniciarActivacion(ID, CONTRASENA, CONTEXTO);

        ArgumentCaptor<String> guardado = ArgumentCaptor.forClass(String.class);
        verify(repositorioDosFactores).guardarActivacionPendiente(eq(ID), guardado.capture(), eq(AHORA));
        // El secreto que se muestra es de 160 bits y es exactamente el que se guarda, cifrado y ligado a este usuario
        byte[] secreto = Base32.decodificar(respuesta.secreto());
        assertThat(secreto).hasSize(20);
        assertThat(cifrador.descifrar(guardado.getValue(), ID)).isEqualTo(secreto);
        assertThat(guardado.getValue()).doesNotContain(respuesta.secreto());
        // y la URI es la del QR de Google Authenticator para ese correo
        assertThat(respuesta.uri()).isEqualTo(UriOtpauth.construir("ESIBuy", EMAIL, secreto));
        verify(auditoria).registrarDosFactoresActivacionIniciada(CONTEXTO, EMAIL, ID);
    }

    @Test
    void iniciarActivacion_dosVeces_daSecretosDistintos() { // CP-2FA-183
        dadoUnUsuario(null);
        when(repositorioDosFactores.guardarActivacionPendiente(eq(ID), anyString(), any())).thenReturn(true);

        String uno = servicio.iniciarActivacion(ID, CONTRASENA, CONTEXTO).secreto();
        String otro = servicio.iniciarActivacion(ID, CONTRASENA, CONTEXTO).secreto();

        assertThat(uno).isNotEqualTo(otro);
    }

    @Test
    void iniciarActivacion_conOtraActivacionPendienteSinConfirmar_laSustituye() { // CP-2FA-184
        dadoUnUsuario(pendiente(AHORA.minusSeconds(60)));
        when(repositorioDosFactores.guardarActivacionPendiente(eq(ID), anyString(), any())).thenReturn(true);

        servicio.iniciarActivacion(ID, CONTRASENA, CONTEXTO);

        verify(repositorioDosFactores).guardarActivacionPendiente(eq(ID), anyString(), eq(AHORA));
    }

    @Test
    void iniciarActivacion_contrasenaIncorrecta_seRechazaYSeCuentaComoFallo() { // CP-2FA-185
        dadoUnUsuario(null);

        Map<String, Set<CodigoError>> errores =
                erroresDe(() -> servicio.iniciarActivacion(ID, "otra-contrasena", CONTEXTO));

        assertThat(errores).containsOnlyKeys("contrasena");
        assertThat(errores.get("contrasena")).containsExactly(CodigoError.CONTRASENA_INCORRECTA);
        verify(limitador).registrarFallo(ID, IP);
        verify(auditoria).registrarDosFactoresFallido(CONTEXTO, EMAIL, ID, MotivoFalloDosFactores.CONTRASENA_INCORRECTA);
        verifyNoInteractions(repositorioDosFactores);
    }

    @Test
    void iniciarActivacion_cuentaYaConDosFactores_lanzaOperacionNoPermitida() { // CP-2FA-186
        dadoUnUsuario(activo(5, hashesDeLosCodigos(10)));

        assertThrows(OperacionNoPermitidaException.class, () -> servicio.iniciarActivacion(ID, CONTRASENA, CONTEXTO));

        verifyNoInteractions(repositorioDosFactores);
    }

    @Test
    void iniciarActivacion_conElLimiteAgotado_lanza429SinComprobarNada() { // CP-2FA-187
        when(limitador.permitido(ID, IP)).thenReturn(false);

        assertThrows(DemasiadosIntentosDosFactoresException.class,
                () -> servicio.iniciarActivacion(ID, CONTRASENA, CONTEXTO));

        verify(auditoria).registrarDosFactoresLimitado(CONTEXTO, ID);
        verifyNoInteractions(codificador, repositorioDosFactores, repositorioUsuario);
    }

    @ParameterizedTest(name = "contrasena \"{0}\"")
    @NullAndEmptySource
    @ValueSource(strings = { " ", "   " })
    void iniciarActivacion_sinContrasena_esUn400QueNoCuentaComoFallo(String contrasena) { // CP-2FA-188
        Map<String, Set<CodigoError>> errores = erroresDe(() -> servicio.iniciarActivacion(ID, contrasena, CONTEXTO));

        assertThat(errores).containsOnlyKeys("contrasena");
        assertThat(errores.get("contrasena")).containsExactly(CodigoError.OBLIGATORIO);
        verify(limitador, never()).registrarFallo(anyString(), anyString());
        verifyNoInteractions(codificador, repositorioDosFactores);
    }

    @Test
    void iniciarActivacion_laContrasenaSeNormalizaANfcYNoSeRecorta() { // CP-2FA-189
        // Igual que en el login: NFC y sin quitar espacios (forman parte de la contrasena)
        String decomposed = Normalizer.normalize("Clave con é final ", Normalizer.Form.NFD);
        String esperada = Normalizer.normalize(decomposed, Normalizer.Form.NFC);
        dadoUnUsuario(null);
        when(codificador.matches(esperada, HASH_CONTRASENA)).thenReturn(true);
        when(repositorioDosFactores.guardarActivacionPendiente(eq(ID), anyString(), any())).thenReturn(true);

        servicio.iniciarActivacion(ID, decomposed, CONTEXTO);

        verify(codificador).matches(esperada, HASH_CONTRASENA);
    }

    @Test
    void iniciarActivacion_siLaCuentaSeActivoEntreMedias_lanzaOperacionNoPermitida() { // CP-2FA-190
        dadoUnUsuario(null);
        when(repositorioDosFactores.guardarActivacionPendiente(eq(ID), anyString(), any())).thenReturn(false);

        assertThrows(OperacionNoPermitidaException.class, () -> servicio.iniciarActivacion(ID, CONTRASENA, CONTEXTO));
    }

    @Test
    void iniciarActivacion_usuarioInexistente_lanzaUsuarioNoEncontrado() { // CP-2FA-191
        when(repositorioUsuario.findById(ID)).thenReturn(Optional.empty());

        assertThrows(UsuarioNoEncontradoException.class, () -> servicio.iniciarActivacion(ID, CONTRASENA, CONTEXTO));
    }

    @Test
    void iniciarActivacion_siFallaLaBaseDeDatos_fallaCerradoSinDetalles() { // CP-2FA-192
        dadoUnUsuario(null);
        when(repositorioDosFactores.guardarActivacionPendiente(eq(ID), anyString(), any()))
                .thenThrow(new IllegalStateException("detalle interno con " + CONTRASENA));

        ServicioNoDisponibleException error = assertThrows(ServicioNoDisponibleException.class,
                () -> servicio.iniciarActivacion(ID, CONTRASENA, CONTEXTO));

        assertThat(error.getCause()).isNull();
        assertThat(String.valueOf(error.getMessage())).doesNotContain(CONTRASENA);
    }

    // ------------------------------------------------------------------ confirmar la activacion

    @Test
    void confirmarActivacion_conElCodigoCorrecto_activaYDevuelveLosCodigosDeRecuperacion() { // CP-2FA-193
        DosFactores pendiente = pendiente(AHORA.minusSeconds(60));
        String cifradoPendiente = pendiente.getPendiente().getSecretoCifrado();
        dadoUnUsuario(pendiente);
        when(repositorioDosFactores.activar(eq(ID), eq(cifradoPendiente), anyLong(), anyList(), eq(AHORA),
                eq(Duration.ofMinutes(10)))).thenReturn(true);

        RespuestaCodigosRecuperacionDTO respuesta = servicio.confirmarActivacion(ID, codigoActual(), CONTEXTO);

        // Se activa con el secreto pendiente EXACTO que se verifico y con el paso del codigo (para que no se reutilice)
        ArgumentCaptor<List<String>> hashes = ArgumentCaptor.forClass(List.class);
        verify(repositorioDosFactores).activar(eq(ID), eq(cifradoPendiente), eq(totp.pasoActual()), hashes.capture(),
                eq(AHORA), eq(Duration.ofMinutes(10)));
        // Diez codigos distintos, con el formato de mostrar, y lo guardado es solo su hash
        assertThat(respuesta.codigosRecuperacion()).hasSize(10).doesNotHaveDuplicates()
                .allMatch(c -> c.matches("^[A-Z2-7]{4}(-[A-Z2-7]{4}){3}$"));
        assertThat(hashes.getValue()).containsExactlyElementsOf(
                respuesta.codigosRecuperacion().stream().map(generadorCodigos::hash).toList());
        assertThat(hashes.getValue()).noneMatch(h -> respuesta.codigosRecuperacion().contains(h));
    }

    @Test
    void confirmarActivacion_alActivar_marcaLasCredencialesYAvisaPorCorreoYAudita() { // CP-2FA-194
        DosFactores pendiente = pendiente(AHORA.minusSeconds(60));
        when(repositorioUsuario.findById(ID)).thenReturn(Optional.of(usuario(pendiente)));
        when(repositorioDosFactores.activar(eq(ID), anyString(), anyLong(), anyList(), any(), any())).thenReturn(true);

        servicio.confirmarActivacion(ID, codigoActual(), CONTEXTO);

        InOrder orden = inOrder(repositorioDosFactores, limitador, notificador, auditoria);
        orden.verify(repositorioDosFactores).activar(eq(ID), anyString(), anyLong(), anyList(), any(), any());
        // Un milisegundo antes del cambio: las demas sesiones se invalidan y la que se renueve ahora sobrevive
        orden.verify(repositorioDosFactores).marcarCredencialesCambiadas(ID, AHORA.minusMillis(1));
        orden.verify(limitador).registrarExito(ID);
        orden.verify(auditoria).registrarDosFactoresActivado(CONTEXTO, EMAIL, ID);
        MensajeCorreo correo = correoEnviado();
        assertThat(correo.destinatario()).isEqualTo(EMAIL);
        assertThat(correo.asunto()).isEqualTo(plantillas.dosFactoresActivado(EMAIL).asunto());
    }

    @Test
    void confirmarActivacion_codigoIncorrecto_seRechazaYSeCuentaComoFallo() { // CP-2FA-195
        dadoUnUsuario(pendiente(AHORA.minusSeconds(60)));
        String incorrecto = codigoTotp(totp.pasoActual() + 5); // fuera de la ventana

        Map<String, Set<CodigoError>> errores = erroresDe(() -> servicio.confirmarActivacion(ID, incorrecto, CONTEXTO));

        assertThat(errores).containsOnlyKeys("codigo");
        assertThat(errores.get("codigo")).containsExactly(CodigoError.CODIGO_INCORRECTO);
        verify(limitador).registrarFallo(ID, IP);
        verify(auditoria).registrarDosFactoresFallido(CONTEXTO, EMAIL, ID, MotivoFalloDosFactores.CODIGO_INCORRECTO);
        verifyNoInteractions(repositorioDosFactores, notificador);
    }

    @Test
    void confirmarActivacion_elCodigoDelPasoAnteriorTodaviaVale() { // CP-2FA-196
        dadoUnUsuario(pendiente(AHORA.minusSeconds(60)));
        when(repositorioDosFactores.activar(eq(ID), anyString(), anyLong(), anyList(), any(), any())).thenReturn(true);

        servicio.confirmarActivacion(ID, codigoTotp(totp.pasoActual() - 1), CONTEXTO);

        verify(repositorioDosFactores).activar(eq(ID), anyString(), eq(totp.pasoActual() - 1), anyList(), any(), any());
    }

    @Test
    void confirmarActivacion_sinActivacionIniciada_noEsVigente() { // CP-2FA-197
        dadoUnUsuario(null);

        Map<String, Set<CodigoError>> errores = erroresDe(() -> servicio.confirmarActivacion(ID, "123456", CONTEXTO));

        assertThat(errores.get("codigo")).containsExactly(CodigoError.ACTIVACION_NO_VIGENTE);
        verify(auditoria).registrarDosFactoresFallido(CONTEXTO, EMAIL, ID, MotivoFalloDosFactores.ACTIVACION_NO_VIGENTE);
        // No es una conjetura del codigo: no suma al limite
        verify(limitador, never()).registrarFallo(anyString(), anyString());
        verifyNoInteractions(repositorioDosFactores);
    }

    @Test
    void confirmarActivacion_elSecretoPendienteCaducaALosDiezMinutosExactos() { // CP-2FA-198
        // A los 9 min 59 s sigue valiendo
        dadoUnUsuario(pendiente(AHORA.minusSeconds(599)));
        when(repositorioDosFactores.activar(eq(ID), anyString(), anyLong(), anyList(), any(), any())).thenReturn(true);
        assertThat(servicio.confirmarActivacion(ID, codigoActual(), CONTEXTO).codigosRecuperacion()).hasSize(10);

        // A los 10 minutos exactos ya no
        dadoUnUsuario(pendiente(AHORA.minusSeconds(600)));
        Map<String, Set<CodigoError>> errores = erroresDe(() -> servicio.confirmarActivacion(ID, codigoActual(), CONTEXTO));
        assertThat(errores.get("codigo")).containsExactly(CodigoError.ACTIVACION_NO_VIGENTE);
    }

    @Test
    void confirmarActivacion_cuentaYaConDosFactores_lanzaOperacionNoPermitida() { // CP-2FA-199
        dadoUnUsuario(activo(5, hashesDeLosCodigos(10)));

        assertThrows(OperacionNoPermitidaException.class, () -> servicio.confirmarActivacion(ID, codigoActual(), CONTEXTO));

        verifyNoInteractions(repositorioDosFactores);
    }

    @ParameterizedTest(name = "codigo \"{0}\"")
    @NullAndEmptySource
    @ValueSource(strings = { " ", "   " })
    void confirmarActivacion_sinCodigo_esUn400QueNoCuentaComoFallo(String codigo) { // CP-2FA-200
        Map<String, Set<CodigoError>> errores = erroresDe(() -> servicio.confirmarActivacion(ID, codigo, CONTEXTO));

        assertThat(errores.get("codigo")).containsExactly(CodigoError.OBLIGATORIO);
        verify(limitador, never()).registrarFallo(anyString(), anyString());
        verifyNoInteractions(repositorioDosFactores);
    }

    @ParameterizedTest(name = "codigo \"{0}\"")
    @ValueSource(strings = { "12345", "1234567", "abcdef", "12 34 5", "ABCD-EFGH-2345-6723", "000000" })
    void confirmarActivacion_codigoQueNoSonSeisDigitosCorrectos_seCuentaComoFallo(String codigo) { // CP-2FA-201
        dadoUnUsuario(pendiente(AHORA.minusSeconds(60)));

        Map<String, Set<CodigoError>> errores = erroresDe(() -> servicio.confirmarActivacion(ID, codigo, CONTEXTO));

        assertThat(errores.get("codigo")).containsExactly(CodigoError.CODIGO_INCORRECTO);
        verify(limitador).registrarFallo(ID, IP);
    }

    @Test
    void confirmarActivacion_conElLimiteAgotado_lanza429SinComprobarNada() { // CP-2FA-202
        when(limitador.permitido(ID, IP)).thenReturn(false);

        assertThrows(DemasiadosIntentosDosFactoresException.class,
                () -> servicio.confirmarActivacion(ID, codigoActual(), CONTEXTO));

        verify(auditoria).registrarDosFactoresLimitado(CONTEXTO, ID);
        verifyNoInteractions(repositorioDosFactores, repositorioUsuario);
    }

    @Test
    void confirmarActivacion_siSeReiniciaLaActivacionEntreMedias_noActivaNada() { // CP-2FA-203
        dadoUnUsuario(pendiente(AHORA.minusSeconds(60)));
        // El repositorio exige que el secreto sea el verificado: otro pendiente nuevo lo hace fallar
        when(repositorioDosFactores.activar(eq(ID), anyString(), anyLong(), anyList(), any(), any())).thenReturn(false);

        assertThrows(OperacionNoPermitidaException.class, () -> servicio.confirmarActivacion(ID, codigoActual(), CONTEXTO));

        verify(repositorioDosFactores, never()).marcarCredencialesCambiadas(anyString(), any());
        verifyNoInteractions(notificador);
        verify(auditoria, never()).registrarDosFactoresActivado(any(), anyString(), anyString());
    }

    @Test
    void confirmarActivacion_siElSecretoPendienteNoSePuedeDescifrar_fallaCerradoSinDetalles() { // CP-2FA-204
        // Cifrado para OTRO usuario: no se puede leer con este id como dato asociado
        dadoUnUsuario(DosFactores.pendiente(cifrador.cifrar(SECRETO, "otro-usuario"), AHORA.minusSeconds(60)));

        ServicioNoDisponibleException error = assertThrows(ServicioNoDisponibleException.class,
                () -> servicio.confirmarActivacion(ID, codigoActual(), CONTEXTO));

        assertThat(error.getCause()).isNull();
        verifyNoInteractions(repositorioDosFactores);
    }

    // ------------------------------------------------------------------ desactivar

    @Test
    void desactivar_conContrasenaYCodigoTotp_quitaElDosFactores() { // CP-2FA-205
        dadoUnUsuario(activo(GeneradorTotp.NINGUN_PASO, hashesDeLosCodigos(10)));
        long paso = totp.pasoActual();
        when(repositorioDosFactores.registrarPasoAceptado(ID, paso)).thenReturn(true);
        when(repositorioDosFactores.desactivar(ID)).thenReturn(true);

        servicio.desactivar(ID, CONTRASENA, codigoActual(), CONTEXTO);

        InOrder orden = inOrder(repositorioDosFactores, limitador, notificador, auditoria);
        orden.verify(repositorioDosFactores).registrarPasoAceptado(ID, paso);
        orden.verify(repositorioDosFactores).desactivar(ID);
        orden.verify(repositorioDosFactores).marcarCredencialesCambiadas(ID, AHORA.minusMillis(1));
        orden.verify(limitador).registrarExito(ID);
        orden.verify(auditoria).registrarDosFactoresDesactivado(CONTEXTO, EMAIL, ID);
        assertThat(correoEnviado().asunto()).isEqualTo(plantillas.dosFactoresDesactivado(EMAIL).asunto());
    }

    @Test
    void desactivar_conUnCodigoDeRecuperacion_loConsumeYAvisaDelUso() { // CP-2FA-206
        String codigo = generadorCodigos.nuevoCodigo();
        List<String> hashes = new ArrayList<>(hashesDeLosCodigos(9));
        hashes.add(generadorCodigos.hash(codigo));
        dadoUnUsuario(activo(GeneradorTotp.NINGUN_PASO, hashes));
        when(repositorioDosFactores.consumirCodigoRecuperacion(ID, generadorCodigos.hash(codigo), AHORA)).thenReturn(true);
        when(repositorioDosFactores.desactivar(ID)).thenReturn(true);

        servicio.desactivar(ID, CONTRASENA, GeneradorCodigosRecuperacion.formatear(codigo).toLowerCase(), CONTEXTO);

        verify(repositorioDosFactores).consumirCodigoRecuperacion(ID, generadorCodigos.hash(codigo), AHORA);
        verify(repositorioDosFactores, never()).registrarPasoAceptado(anyString(), anyLong());
        verify(repositorioDosFactores).desactivar(ID);
        // Alerta del uso (le quedaban 9 de 10) y un solo correo: el de que se ha desactivado
        verify(auditoria).registrarDosFactoresCodigoRecuperacionUsado(CONTEXTO, EMAIL, ID, 9);
        assertThat(correoEnviado().asunto()).isEqualTo(plantillas.dosFactoresDesactivado(EMAIL).asunto());
        verifyNoMoreInteractions(notificador);
    }

    @Test
    void desactivar_contrasenaIncorrecta_noLlegaAProbarElCodigo() { // CP-2FA-207
        dadoUnUsuario(activo(GeneradorTotp.NINGUN_PASO, hashesDeLosCodigos(10)));

        Map<String, Set<CodigoError>> errores =
                erroresDe(() -> servicio.desactivar(ID, "otra-contrasena", codigoActual(), CONTEXTO));

        assertThat(errores).containsOnlyKeys("contrasena");
        assertThat(errores.get("contrasena")).containsExactly(CodigoError.CONTRASENA_INCORRECTA);
        verify(limitador).registrarFallo(ID, IP);
        verifyNoInteractions(repositorioDosFactores, notificador);
    }

    @Test
    void desactivar_codigoIncorrecto_seRechazaYSeCuentaComoFallo() { // CP-2FA-208
        dadoUnUsuario(activo(GeneradorTotp.NINGUN_PASO, hashesDeLosCodigos(10)));

        Map<String, Set<CodigoError>> errores =
                erroresDe(() -> servicio.desactivar(ID, CONTRASENA, "000000", CONTEXTO));

        assertThat(errores).containsOnlyKeys("codigo");
        assertThat(errores.get("codigo")).containsExactly(CodigoError.CODIGO_INCORRECTO);
        verify(limitador).registrarFallo(ID, IP);
        verify(auditoria).registrarDosFactoresFallido(CONTEXTO, EMAIL, ID, MotivoFalloDosFactores.CODIGO_INCORRECTO);
        verify(repositorioDosFactores, never()).desactivar(anyString());
        verifyNoInteractions(notificador);
    }

    @Test
    void desactivar_totpYaAceptado_seRechazaComoReutilizadoYCuentaComoFallo() { // CP-2FA-209
        dadoUnUsuario(activo(GeneradorTotp.NINGUN_PASO, hashesDeLosCodigos(10)));
        // El codigo es correcto, pero otra peticion ya lo gasto: el registro atomico del paso devuelve falso
        when(repositorioDosFactores.registrarPasoAceptado(ID, totp.pasoActual())).thenReturn(false);

        Map<String, Set<CodigoError>> errores =
                erroresDe(() -> servicio.desactivar(ID, CONTRASENA, codigoActual(), CONTEXTO));

        assertThat(errores.get("codigo")).containsExactly(CodigoError.CODIGO_INCORRECTO);
        verify(auditoria).registrarDosFactoresFallido(CONTEXTO, EMAIL, ID, MotivoFalloDosFactores.CODIGO_REUTILIZADO);
        verify(limitador).registrarFallo(ID, IP);
        verify(repositorioDosFactores, never()).desactivar(anyString());
    }

    @Test
    void desactivar_codigoDeRecuperacionYaUsadoODesconocido_seRechaza() { // CP-2FA-210
        dadoUnUsuario(activo(GeneradorTotp.NINGUN_PASO, hashesDeLosCodigos(10)));
        String ajeno = generadorCodigos.nuevoCodigo();
        when(repositorioDosFactores.consumirCodigoRecuperacion(eq(ID), anyString(), any())).thenReturn(false);

        Map<String, Set<CodigoError>> errores =
                erroresDe(() -> servicio.desactivar(ID, CONTRASENA, ajeno, CONTEXTO));

        assertThat(errores.get("codigo")).containsExactly(CodigoError.CODIGO_INCORRECTO);
        verify(limitador).registrarFallo(ID, IP);
        verify(repositorioDosFactores, never()).desactivar(anyString());
    }

    @Test
    void desactivar_sinDosFactoresActivo_lanzaOperacionNoPermitida() { // CP-2FA-211
        dadoUnUsuario(null);
        assertThrows(OperacionNoPermitidaException.class, () -> servicio.desactivar(ID, CONTRASENA, "123456", CONTEXTO));

        dadoUnUsuario(pendiente(AHORA));
        assertThrows(OperacionNoPermitidaException.class, () -> servicio.desactivar(ID, CONTRASENA, "123456", CONTEXTO));

        verifyNoInteractions(repositorioDosFactores);
    }

    @Test
    void desactivar_conElLimiteAgotado_lanza429SinComprobarLaContrasena() { // CP-2FA-212
        when(limitador.permitido(ID, IP)).thenReturn(false);

        assertThrows(DemasiadosIntentosDosFactoresException.class,
                () -> servicio.desactivar(ID, CONTRASENA, codigoActual(), CONTEXTO));

        verify(auditoria).registrarDosFactoresLimitado(CONTEXTO, ID);
        verifyNoInteractions(codificador, repositorioDosFactores, repositorioUsuario);
    }

    static Stream<Arguments> camposVacios() {
        return Stream.of(
                Arguments.of(null, "123456", Set.of("contrasena")),
                Arguments.of("", "123456", Set.of("contrasena")),
                Arguments.of(CONTRASENA, null, Set.of("codigo")),
                Arguments.of(CONTRASENA, "  ", Set.of("codigo")),
                Arguments.of(null, null, Set.of("contrasena", "codigo")));
    }

    @ParameterizedTest(name = "contrasena=\"{0}\" codigo=\"{1}\"")
    @MethodSource("camposVacios")
    void desactivar_camposVacios_esUn400ConTodosLosCamposQueNoCuentaComoFallo(String contrasena, String codigo,
            Set<String> camposConError) { // CP-2FA-213
        Map<String, Set<CodigoError>> errores = erroresDe(() -> servicio.desactivar(ID, contrasena, codigo, CONTEXTO));

        assertThat(errores).containsOnlyKeys(camposConError);
        assertThat(errores.values()).allSatisfy(codigos -> assertThat(codigos).containsExactly(CodigoError.OBLIGATORIO));
        verify(limitador, never()).registrarFallo(anyString(), anyString());
        verifyNoInteractions(repositorioDosFactores);
    }

    @Test
    void desactivar_siLaCuentaYaNoTieneDosFactores_lanzaOperacionNoPermitidaSinAvisar() { // CP-2FA-214
        dadoUnUsuario(activo(GeneradorTotp.NINGUN_PASO, hashesDeLosCodigos(10)));
        when(repositorioDosFactores.registrarPasoAceptado(ID, totp.pasoActual())).thenReturn(true);
        when(repositorioDosFactores.desactivar(ID)).thenReturn(false); // otra peticion se adelanto

        assertThrows(OperacionNoPermitidaException.class,
                () -> servicio.desactivar(ID, CONTRASENA, codigoActual(), CONTEXTO));

        verify(repositorioDosFactores, never()).marcarCredencialesCambiadas(anyString(), any());
        verifyNoInteractions(notificador);
        verify(auditoria, never()).registrarDosFactoresDesactivado(any(), anyString(), anyString());
    }

    @Test
    void desactivar_siElSecretoNoSePuedeDescifrar_fallaCerradoSinDetalles() { // CP-2FA-215
        dadoUnUsuario(DosFactores.activo(cifrador.cifrar(SECRETO, "otro-usuario"), AHORA, GeneradorTotp.NINGUN_PASO,
                codigosSinUsar(hashesDeLosCodigos(3))));

        ServicioNoDisponibleException error = assertThrows(ServicioNoDisponibleException.class,
                () -> servicio.desactivar(ID, CONTRASENA, codigoActual(), CONTEXTO));

        assertThat(error.getCause()).isNull();
        verify(repositorioDosFactores, never()).desactivar(anyString());
    }

    // ------------------------------------------------------------------ regenerar los codigos

    @Test
    void regenerarCodigos_conContrasenaYCodigoTotp_sustituyeLosCodigosYDevuelveLosNuevos() { // CP-2FA-216
        dadoUnUsuario(activo(GeneradorTotp.NINGUN_PASO, hashesDeLosCodigos(10)));
        when(repositorioDosFactores.registrarPasoAceptado(ID, totp.pasoActual())).thenReturn(true);
        when(repositorioDosFactores.reemplazarCodigosRecuperacion(eq(ID), anyList())).thenReturn(true);

        RespuestaCodigosRecuperacionDTO respuesta = servicio.regenerarCodigos(ID, CONTRASENA, codigoActual(), CONTEXTO);

        ArgumentCaptor<List<String>> hashes = ArgumentCaptor.forClass(List.class);
        verify(repositorioDosFactores).reemplazarCodigosRecuperacion(eq(ID), hashes.capture());
        assertThat(respuesta.codigosRecuperacion()).hasSize(10).doesNotHaveDuplicates()
                .allMatch(c -> c.matches("^[A-Z2-7]{4}(-[A-Z2-7]{4}){3}$"));
        assertThat(hashes.getValue()).containsExactlyElementsOf(
                respuesta.codigosRecuperacion().stream().map(generadorCodigos::hash).toList());
    }

    @Test
    void regenerarCodigos_alTerminar_marcaLasCredencialesYAvisaPorCorreoYAudita() { // CP-2FA-217
        dadoUnUsuario(activo(GeneradorTotp.NINGUN_PASO, hashesDeLosCodigos(10)));
        when(repositorioDosFactores.registrarPasoAceptado(ID, totp.pasoActual())).thenReturn(true);
        when(repositorioDosFactores.reemplazarCodigosRecuperacion(eq(ID), anyList())).thenReturn(true);

        servicio.regenerarCodigos(ID, CONTRASENA, codigoActual(), CONTEXTO);

        InOrder orden = inOrder(repositorioDosFactores, limitador, auditoria);
        orden.verify(repositorioDosFactores).reemplazarCodigosRecuperacion(eq(ID), anyList());
        orden.verify(repositorioDosFactores).marcarCredencialesCambiadas(ID, AHORA.minusMillis(1));
        orden.verify(limitador).registrarExito(ID);
        orden.verify(auditoria).registrarDosFactoresCodigosRegenerados(CONTEXTO, EMAIL, ID);
        assertThat(correoEnviado().asunto()).isEqualTo(plantillas.codigosRecuperacionRegenerados(EMAIL).asunto());
    }

    @Test
    void regenerarCodigos_verificandoConUnCodigoDeRecuperacion_loConsumeAntesDeReemplazar() { // CP-2FA-218
        String codigo = generadorCodigos.nuevoCodigo();
        List<String> hashes = new ArrayList<>(hashesDeLosCodigos(9));
        hashes.add(generadorCodigos.hash(codigo));
        dadoUnUsuario(activo(GeneradorTotp.NINGUN_PASO, hashes));
        when(repositorioDosFactores.consumirCodigoRecuperacion(ID, generadorCodigos.hash(codigo), AHORA)).thenReturn(true);
        when(repositorioDosFactores.reemplazarCodigosRecuperacion(eq(ID), anyList())).thenReturn(true);

        servicio.regenerarCodigos(ID, CONTRASENA, GeneradorCodigosRecuperacion.formatear(codigo), CONTEXTO);

        InOrder orden = inOrder(repositorioDosFactores);
        orden.verify(repositorioDosFactores).consumirCodigoRecuperacion(ID, generadorCodigos.hash(codigo), AHORA);
        orden.verify(repositorioDosFactores).reemplazarCodigosRecuperacion(eq(ID), anyList());
        verify(auditoria).registrarDosFactoresCodigoRecuperacionUsado(CONTEXTO, EMAIL, ID, 9);
    }

    @Test
    void regenerarCodigos_contrasenaOCodigoIncorrectos_noCambiaNadaYCuentaComoFallo() { // CP-2FA-219
        dadoUnUsuario(activo(GeneradorTotp.NINGUN_PASO, hashesDeLosCodigos(10)));

        assertThat(erroresDe(() -> servicio.regenerarCodigos(ID, "otra", codigoActual(), CONTEXTO)))
                .containsOnlyKeys("contrasena");
        assertThat(erroresDe(() -> servicio.regenerarCodigos(ID, CONTRASENA, "000000", CONTEXTO)))
                .containsOnlyKeys("codigo");

        verify(limitador, org.mockito.Mockito.times(2)).registrarFallo(ID, IP);
        verify(repositorioDosFactores, never()).reemplazarCodigosRecuperacion(anyString(), anyList());
        verifyNoInteractions(notificador);
    }

    @Test
    void regenerarCodigos_sinDosFactoresActivo_lanzaOperacionNoPermitida() { // CP-2FA-220
        dadoUnUsuario(null);

        assertThrows(OperacionNoPermitidaException.class,
                () -> servicio.regenerarCodigos(ID, CONTRASENA, "123456", CONTEXTO));
    }

    @Test
    void regenerarCodigos_siLaCuentaYaNoTieneDosFactores_lanzaOperacionNoPermitidaSinAvisar() { // CP-2FA-221
        dadoUnUsuario(activo(GeneradorTotp.NINGUN_PASO, hashesDeLosCodigos(10)));
        when(repositorioDosFactores.registrarPasoAceptado(ID, totp.pasoActual())).thenReturn(true);
        when(repositorioDosFactores.reemplazarCodigosRecuperacion(eq(ID), anyList())).thenReturn(false);

        assertThrows(OperacionNoPermitidaException.class,
                () -> servicio.regenerarCodigos(ID, CONTRASENA, codigoActual(), CONTEXTO));

        verify(repositorioDosFactores, never()).marcarCredencialesCambiadas(anyString(), any());
        verifyNoInteractions(notificador);
    }

    @Test
    void regenerarCodigos_conElLimiteAgotado_lanza429() { // CP-2FA-222
        when(limitador.permitido(ID, IP)).thenReturn(false);

        assertThrows(DemasiadosIntentosDosFactoresException.class,
                () -> servicio.regenerarCodigos(ID, CONTRASENA, codigoActual(), CONTEXTO));

        verifyNoInteractions(codificador, repositorioDosFactores);
    }

    @Test
    void regenerarCodigos_totpYaAceptado_seRechazaComoReutilizado() { // CP-2FA-223
        dadoUnUsuario(activo(GeneradorTotp.NINGUN_PASO, hashesDeLosCodigos(10)));
        when(repositorioDosFactores.registrarPasoAceptado(ID, totp.pasoActual())).thenReturn(false);

        assertThat(erroresDe(() -> servicio.regenerarCodigos(ID, CONTRASENA, codigoActual(), CONTEXTO)))
                .containsOnlyKeys("codigo");
        verify(auditoria).registrarDosFactoresFallido(CONTEXTO, EMAIL, ID, MotivoFalloDosFactores.CODIGO_REUTILIZADO);
    }

    @Test
    void regenerarCodigos_laCantidadSigueLaConfiguracion() { // CP-2FA-224
        ServicioDosFactores conOcho = new ServicioDosFactores(repositorioUsuario, repositorioDosFactores, cifrador, totp,
                generadorCodigos, codificador, limitador, notificador, plantillas, auditoria,
                PropiedadesDosFactoresPrueba.con(10, 30, 8), reloj);
        dadoUnUsuario(activo(GeneradorTotp.NINGUN_PASO, hashesDeLosCodigos(10)));
        when(repositorioDosFactores.registrarPasoAceptado(ID, totp.pasoActual())).thenReturn(true);
        when(repositorioDosFactores.reemplazarCodigosRecuperacion(eq(ID), anyList())).thenReturn(true);

        assertThat(conOcho.regenerarCodigos(ID, CONTRASENA, codigoActual(), CONTEXTO).codigosRecuperacion()).hasSize(8);
    }

    // ------------------------------------------------------------------ nada sensible en los logs

    @Test
    void ningunaOperacion_escribeContrasenasCodigosNiSecretosEnLosLogsNiEnLosErrores() { // CP-2FA-225
        AuditoriaSeguridad auditoriaReal = new AuditoriaSeguridad();
        ServicioDosFactores conAuditoriaReal = crearServicio(auditoriaReal);
        try (CapturadorLogs logs = CapturadorLogs.iniciar()) {
            // Activar con exito, fallar de varias formas y regenerar, para que se escriban todos los eventos posibles
            dadoUnUsuario(null);
            when(repositorioDosFactores.guardarActivacionPendiente(eq(ID), anyString(), any())).thenReturn(true);
            RespuestaActivacionDosFactoresDTO activacion = conAuditoriaReal.iniciarActivacion(ID, CONTRASENA, CONTEXTO);

            dadoUnUsuario(pendiente(AHORA.minusSeconds(30)));
            when(repositorioDosFactores.activar(eq(ID), anyString(), anyLong(), anyList(), any(), any())).thenReturn(true);
            String codigoDeActivacion = codigoActual();
            RespuestaCodigosRecuperacionDTO codigos = conAuditoriaReal.confirmarActivacion(ID, codigoDeActivacion, CONTEXTO);
            assertThrows(DatosDosFactoresInvalidosException.class,
                    () -> conAuditoriaReal.confirmarActivacion(ID, "654321", CONTEXTO));

            dadoUnUsuario(activo(GeneradorTotp.NINGUN_PASO, hashesDeLosCodigos(10)));
            String errada = "Otra-Contrasena-99!";
            assertThrows(DatosDosFactoresInvalidosException.class,
                    () -> conAuditoriaReal.desactivar(ID, errada, "000000", CONTEXTO));

            List<String> secretosYCodigos = new ArrayList<>(codigos.codigosRecuperacion());
            secretosYCodigos.addAll(List.of(CONTRASENA, errada, codigoDeActivacion, activacion.secreto(),
                    Base32.codificar(SECRETO), "000000", "654321"));
            assertThat(logs.textosCompletos()).isNotEmpty().allSatisfy(texto -> {
                for (String secreto : secretosYCodigos) {
                    assertThat(texto).doesNotContain(secreto);
                }
            });
        }
    }
}
