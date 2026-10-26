package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.esibuy.esibuy_backend.excepcion.CodigoError;
import com.esibuy.esibuy_backend.excepcion.DatosDosFactoresInvalidosException;
import com.esibuy.esibuy_backend.excepcion.DemasiadosIntentosDosFactoresException;
import com.esibuy.esibuy_backend.excepcion.SegundoFactorNoVigenteException;
import com.esibuy.esibuy_backend.excepcion.ServicioNoDisponibleException;
import com.esibuy.esibuy_backend.modelo.DosFactores;
import com.esibuy.esibuy_backend.modelo.DosFactores.CodigoRecuperacion;
import com.esibuy.esibuy_backend.modelo.EstadoUsuario;
import com.esibuy.esibuy_backend.modelo.PerfilUsuario;
import com.esibuy.esibuy_backend.modelo.Rol;
import com.esibuy.esibuy_backend.modelo.Usuario;
import com.esibuy.esibuy_backend.repositorio.RepositorioDosFactores;
import com.esibuy.esibuy_backend.repositorio.RepositorioUsuario;
import com.esibuy.esibuy_backend.util.PropiedadesCorreoPrueba;
import com.esibuy.esibuy_backend.util.PropiedadesDosFactoresPrueba;
import com.esibuy.esibuy_backend.util.RelojMutable;

/**
 * Verificacion del codigo del segundo paso del login (HdU 2FA, fase 3): ServicioDosFactores.verificarParaLogin.
 * Casos: CP-2FA-312 a CP-2FA-328.
 *
 * Es la misma comprobacion que usa regenerar los codigos (el TOTP del movil o un codigo de recuperacion), con las mismas
 * garantias: un TOTP ya aceptado no vale otra vez (anti-reutilizacion atomica con el ultimo paso aceptado, que se comparte
 * con esas operaciones), un codigo de recuperacion se consume una sola vez, y los fallos suman al limite de la cuenta y la IP.
 * Aqui no se pide la contrasena (ya se comprobo en el primer paso) ni se marcan las credenciales como cambiadas (entrar no
 * cambia nada de la cuenta) ni se avisa por correo.
 *
 * Orden: codigo vacio (un 400 que no cuenta como fallo), limite de fallos, usuario y, solo entonces, el codigo. Si entre los
 * dos pasos la cuenta dejo de poder entrar (borrada, bloqueada, desactivada) o ya no tiene el 2FA activo, el desafio no es
 * vigente (401): es el mismo error que un desafio caducado. Acertar el codigo audita LOGIN_CORRECTO; usar un codigo de
 * recuperacion es ademas una alerta con los codigos que quedan. Fallos internos: 503 sin detalles.
 *
 * Terminada: ya no lleva la etiqueta pendiente-2fa y entra en la regresion.
 */
@ExtendWith(MockitoExtension.class)
class ServicioDosFactoresLoginTest {

    private static final String ID = "665f1c2e9b1e8a3d4c5b6a79";
    private static final String EMAIL = "ana.garcia@ejemplo.es";
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
    private final CifradorSecretos cifrador = new CifradorSecretos(Map.of(1, PropiedadesDosFactoresPrueba.CLAVE), 1);
    private final GeneradorTotp totp = new GeneradorTotp(reloj);
    private final GeneradorCodigosRecuperacion generadorCodigos = new GeneradorCodigosRecuperacion(PEPPER);

    private ServicioDosFactores servicio;

    @BeforeEach
    void prepararServicio() {
        lenient().when(limitador.permitido(anyString(), anyString())).thenReturn(true);
        servicio = new ServicioDosFactores(repositorioUsuario, repositorioDosFactores, cifrador, totp, generadorCodigos,
                codificador, limitador, notificador, new PlantillasCorreo(PropiedadesCorreoPrueba.remoto().construir()),
                auditoria, PropiedadesDosFactoresPrueba.acordadas(), reloj);
    }

    // ------------------------------------------------------------------ utilidades

    private static Usuario usuario(EstadoUsuario estado, DosFactores dosFactores) {
        Usuario usuario = new Usuario(EMAIL, "hash", Rol.CLIENTE,
                PerfilUsuario.builder().nombre("Ana").apellidos("Garcia").build());
        ReflectionTestUtils.setField(usuario, "id", ID);
        ReflectionTestUtils.setField(usuario, "estado", estado);
        ReflectionTestUtils.setField(usuario, "dosFactores", dosFactores);
        return usuario;
    }

    private List<String> hashesDeLosCodigos(int cantidad) {
        List<String> hashes = new ArrayList<>();
        for (int i = 0; i < cantidad; i++) {
            hashes.add(generadorCodigos.hash(generadorCodigos.nuevoCodigo()));
        }
        return hashes;
    }

    private DosFactores activo(long ultimoPaso, List<String> hashes) {
        return DosFactores.activo(cifrador.cifrar(SECRETO, ID), AHORA.minusSeconds(3600), ultimoPaso,
                hashes.stream().map(CodigoRecuperacion::nuevo).toList());
    }

    private void dadoUnUsuarioActivoConDosFactores(long ultimoPaso, List<String> hashes) {
        when(repositorioUsuario.findById(ID)).thenReturn(Optional.of(
                usuario(EstadoUsuario.ACTIVO, activo(ultimoPaso, hashes))));
    }

    private String codigoActual() {
        return totp.codigo(SECRETO, totp.pasoActual());
    }

    private static Map<String, Set<CodigoError>> erroresDe(Executable accion) {
        return assertThrows(DatosDosFactoresInvalidosException.class, accion).getErrores();
    }

    // ------------------------------------------------------------------ acierto

    @Test
    void verificarParaLogin_conElTotpCorrecto_registraElPasoYAuditaElLoginCorrecto() { // CP-2FA-312
        dadoUnUsuarioActivoConDosFactores(GeneradorTotp.NINGUN_PASO, hashesDeLosCodigos(10));
        when(repositorioDosFactores.registrarPasoAceptado(ID, totp.pasoActual())).thenReturn(true);

        servicio.verificarParaLogin(ID, codigoActual(), CONTEXTO);

        InOrder orden = inOrder(limitador, repositorioDosFactores, auditoria);
        orden.verify(limitador).permitido(ID, IP);
        orden.verify(repositorioDosFactores).registrarPasoAceptado(ID, totp.pasoActual());
        orden.verify(limitador).registrarExito(ID);
        orden.verify(auditoria).registrarLoginCorrecto(CONTEXTO, EMAIL, ID);
    }

    @Test
    void verificarParaLogin_conElTotpEscritoConEspacios_tambienVale() { // CP-2FA-313
        dadoUnUsuarioActivoConDosFactores(GeneradorTotp.NINGUN_PASO, hashesDeLosCodigos(10));
        when(repositorioDosFactores.registrarPasoAceptado(ID, totp.pasoActual())).thenReturn(true);
        String codigo = codigoActual();

        servicio.verificarParaLogin(ID, codigo.substring(0, 3) + " " + codigo.substring(3), CONTEXTO);

        verify(auditoria).registrarLoginCorrecto(CONTEXTO, EMAIL, ID);
    }

    @Test
    void verificarParaLogin_elCodigoDelPasoAnteriorTodaviaVale() { // CP-2FA-314
        dadoUnUsuarioActivoConDosFactores(GeneradorTotp.NINGUN_PASO, hashesDeLosCodigos(10));
        long anterior = totp.pasoActual() - 1;
        when(repositorioDosFactores.registrarPasoAceptado(ID, anterior)).thenReturn(true);

        servicio.verificarParaLogin(ID, totp.codigo(SECRETO, anterior), CONTEXTO);

        verify(repositorioDosFactores).registrarPasoAceptado(ID, anterior);
    }

    @Test
    void verificarParaLogin_conUnCodigoDeRecuperacion_loConsumeYAlertaDeLosQueQuedan() { // CP-2FA-315
        String codigo = generadorCodigos.nuevoCodigo();
        List<String> hashes = new ArrayList<>(hashesDeLosCodigos(9));
        hashes.add(generadorCodigos.hash(codigo));
        dadoUnUsuarioActivoConDosFactores(GeneradorTotp.NINGUN_PASO, hashes);
        when(repositorioDosFactores.consumirCodigoRecuperacion(ID, generadorCodigos.hash(codigo), AHORA)).thenReturn(true);

        // Escrito como lo teclearia alguien: minusculas, con guiones
        servicio.verificarParaLogin(ID, GeneradorCodigosRecuperacion.formatear(codigo).toLowerCase(), CONTEXTO);

        verify(repositorioDosFactores, never()).registrarPasoAceptado(anyString(), anyLong());
        // Le quedaban 10 y usa uno: quedan 9. Es una alerta, y ademas el login es correcto
        verify(auditoria).registrarDosFactoresCodigoRecuperacionUsado(CONTEXTO, EMAIL, ID, 9);
        verify(auditoria).registrarLoginCorrecto(CONTEXTO, EMAIL, ID);
        verify(limitador).registrarExito(ID);
    }

    @Test
    void verificarParaLogin_noPideLaContrasenaNiMarcaCredencialesNiAvisaPorCorreo() { // CP-2FA-316
        dadoUnUsuarioActivoConDosFactores(GeneradorTotp.NINGUN_PASO, hashesDeLosCodigos(10));
        when(repositorioDosFactores.registrarPasoAceptado(ID, totp.pasoActual())).thenReturn(true);

        servicio.verificarParaLogin(ID, codigoActual(), CONTEXTO);

        // Entrar no cambia nada de la cuenta: no invalida sesiones ni avisa de nada
        verifyNoInteractions(codificador, notificador);
        verify(repositorioDosFactores, never()).marcarCredencialesCambiadas(anyString(), any());
    }

    // ------------------------------------------------------------------ fallos

    @Test
    void verificarParaLogin_codigoIncorrecto_seRechazaYSeCuentaComoFallo() { // CP-2FA-317
        dadoUnUsuarioActivoConDosFactores(GeneradorTotp.NINGUN_PASO, hashesDeLosCodigos(10));

        Map<String, Set<CodigoError>> errores = erroresDe(() -> servicio.verificarParaLogin(ID, "000000", CONTEXTO));

        assertThat(errores).containsOnlyKeys("codigo");
        assertThat(errores.get("codigo")).containsExactly(CodigoError.CODIGO_INCORRECTO);
        verify(limitador).registrarFallo(ID, IP);
        verify(auditoria).registrarDosFactoresFallido(CONTEXTO, EMAIL, ID, MotivoFalloDosFactores.CODIGO_INCORRECTO);
        verify(auditoria, never()).registrarLoginCorrecto(any(), anyString(), anyString());
        verify(limitador, never()).registrarExito(anyString());
    }

    @Test
    void verificarParaLogin_totpYaAceptado_seRechazaComoReutilizadoYCuentaComoFallo() { // CP-2FA-318
        dadoUnUsuarioActivoConDosFactores(GeneradorTotp.NINGUN_PASO, hashesDeLosCodigos(10));
        // Correcto, pero otra peticion (o la activacion) ya lo gasto: el registro atomico del paso devuelve falso
        when(repositorioDosFactores.registrarPasoAceptado(ID, totp.pasoActual())).thenReturn(false);

        Map<String, Set<CodigoError>> errores = erroresDe(() -> servicio.verificarParaLogin(ID, codigoActual(), CONTEXTO));

        assertThat(errores.get("codigo")).containsExactly(CodigoError.CODIGO_INCORRECTO);
        verify(auditoria).registrarDosFactoresFallido(CONTEXTO, EMAIL, ID, MotivoFalloDosFactores.CODIGO_REUTILIZADO);
        verify(limitador).registrarFallo(ID, IP);
        verify(auditoria, never()).registrarLoginCorrecto(any(), anyString(), anyString());
    }

    @Test
    void verificarParaLogin_totpDeUnPasoAnteriorAlUltimoAceptado_seRechaza() { // CP-2FA-319
        // Ya se acepto el paso actual: ni el mismo codigo ni uno anterior valen
        long actual = totp.pasoActual();
        dadoUnUsuarioActivoConDosFactores(actual, hashesDeLosCodigos(10));

        Map<String, Set<CodigoError>> errores = erroresDe(() -> servicio.verificarParaLogin(ID, codigoActual(), CONTEXTO));

        assertThat(errores.get("codigo")).containsExactly(CodigoError.CODIGO_INCORRECTO);
        verify(repositorioDosFactores, never()).registrarPasoAceptado(anyString(), anyLong());
    }

    @Test
    void verificarParaLogin_codigoDeRecuperacionYaUsadoODesconocido_seRechaza() { // CP-2FA-320
        dadoUnUsuarioActivoConDosFactores(GeneradorTotp.NINGUN_PASO, hashesDeLosCodigos(10));
        when(repositorioDosFactores.consumirCodigoRecuperacion(eq(ID), anyString(), any())).thenReturn(false);

        Map<String, Set<CodigoError>> errores =
                erroresDe(() -> servicio.verificarParaLogin(ID, generadorCodigos.nuevoCodigo(), CONTEXTO));

        assertThat(errores.get("codigo")).containsExactly(CodigoError.CODIGO_INCORRECTO);
        verify(limitador).registrarFallo(ID, IP);
        verify(auditoria, never()).registrarDosFactoresCodigoRecuperacionUsado(any(), anyString(), anyString(), anyInt());
    }

    @ParameterizedTest(name = "codigo \"{0}\"")
    @NullAndEmptySource
    @ValueSource(strings = { " ", "   " })
    void verificarParaLogin_sinCodigo_esUn400QueNoCuentaComoFallo(String codigo) { // CP-2FA-321
        Map<String, Set<CodigoError>> errores = erroresDe(() -> servicio.verificarParaLogin(ID, codigo, CONTEXTO));

        assertThat(errores.get("codigo")).containsExactly(CodigoError.OBLIGATORIO);
        verify(limitador, never()).registrarFallo(anyString(), anyString());
        verifyNoInteractions(repositorioUsuario, repositorioDosFactores);
    }

    @Test
    void verificarParaLogin_conElLimiteAgotado_lanza429SinComprobarNada() { // CP-2FA-322
        when(limitador.permitido(ID, IP)).thenReturn(false);

        assertThrows(DemasiadosIntentosDosFactoresException.class,
                () -> servicio.verificarParaLogin(ID, codigoActual(), CONTEXTO));

        verify(auditoria).registrarDosFactoresLimitado(CONTEXTO, ID);
        verifyNoInteractions(repositorioUsuario, repositorioDosFactores);
        verify(auditoria, never()).registrarLoginCorrecto(any(), anyString(), anyString());
    }

    // ------------------------------------------------------------------ la cuenta cambio entre los dos pasos

    @Test
    void verificarParaLogin_usuarioInexistente_noEsVigente() { // CP-2FA-323
        when(repositorioUsuario.findById(ID)).thenReturn(Optional.empty());

        assertThrows(SegundoFactorNoVigenteException.class, () -> servicio.verificarParaLogin(ID, codigoActual(), CONTEXTO));

        verify(auditoria).registrarLoginSegundoFactorNoVigente(CONTEXTO);
        verifyNoInteractions(repositorioDosFactores);
    }

    @ParameterizedTest(name = "cuenta {0}")
    @EnumSource(value = EstadoUsuario.class, names = { "DESACTIVADO", "BLOQUEADO" })
    void verificarParaLogin_cuentaQueYaNoPuedeEntrar_noEsVigente(EstadoUsuario estado) { // CP-2FA-324
        // Entre el primer y el segundo paso un administrador la bloqueo o desactivo: aunque el codigo sea bueno, no entra
        when(repositorioUsuario.findById(ID)).thenReturn(Optional.of(
                usuario(estado, activo(GeneradorTotp.NINGUN_PASO, hashesDeLosCodigos(10)))));

        assertThrows(SegundoFactorNoVigenteException.class, () -> servicio.verificarParaLogin(ID, codigoActual(), CONTEXTO));

        verify(auditoria).registrarLoginSegundoFactorNoVigente(CONTEXTO);
        verifyNoInteractions(repositorioDosFactores);
        verify(auditoria, never()).registrarLoginCorrecto(any(), anyString(), anyString());
    }

    @Test
    void verificarParaLogin_siElDosFactoresYaNoEstaActivo_noEsVigente() { // CP-2FA-325
        // Lo desactivo desde otra sesion entre los dos pasos
        when(repositorioUsuario.findById(ID)).thenReturn(Optional.of(usuario(EstadoUsuario.ACTIVO, null)));

        assertThrows(SegundoFactorNoVigenteException.class, () -> servicio.verificarParaLogin(ID, codigoActual(), CONTEXTO));

        verifyNoInteractions(repositorioDosFactores);
    }

    // ------------------------------------------------------------------ errores internos

    @Test
    void verificarParaLogin_siElSecretoNoSePuedeDescifrar_fallaCerradoSinDetalles() { // CP-2FA-326
        when(repositorioUsuario.findById(ID)).thenReturn(Optional.of(usuario(EstadoUsuario.ACTIVO,
                DosFactores.activo(cifrador.cifrar(SECRETO, "otro-usuario"), AHORA, GeneradorTotp.NINGUN_PASO,
                        List.of(CodigoRecuperacion.nuevo(generadorCodigos.hash(generadorCodigos.nuevoCodigo())))))));

        ServicioNoDisponibleException error = assertThrows(ServicioNoDisponibleException.class,
                () -> servicio.verificarParaLogin(ID, codigoActual(), CONTEXTO));

        assertThat(error.getCause()).isNull();
        verify(auditoria, never()).registrarLoginCorrecto(any(), anyString(), anyString());
    }

    @Test
    void verificarParaLogin_siFallaLaBaseDeDatos_fallaCerradoSinDetalles() { // CP-2FA-327
        dadoUnUsuarioActivoConDosFactores(GeneradorTotp.NINGUN_PASO, hashesDeLosCodigos(10));
        when(repositorioDosFactores.registrarPasoAceptado(anyString(), anyLong()))
                .thenThrow(new IllegalStateException("detalle interno"));

        ServicioNoDisponibleException error = assertThrows(ServicioNoDisponibleException.class,
                () -> servicio.verificarParaLogin(ID, codigoActual(), CONTEXTO));

        assertThat(error.getCause()).isNull();
        verify(auditoria, never()).registrarLoginCorrecto(any(), anyString(), anyString());
    }

    @Test
    void verificarParaLogin_elTotpAceptadoAhoraNoVale2VecesNiParaRegenerar() { // CP-2FA-328
        // El paso aceptado en el login se comparte con regenerar los codigos: el mismo registro atomico
        dadoUnUsuarioActivoConDosFactores(GeneradorTotp.NINGUN_PASO, hashesDeLosCodigos(10));
        when(repositorioDosFactores.registrarPasoAceptado(ID, totp.pasoActual())).thenReturn(true, false);

        servicio.verificarParaLogin(ID, codigoActual(), CONTEXTO);
        assertThrows(DatosDosFactoresInvalidosException.class, () -> servicio.verificarParaLogin(ID, codigoActual(), CONTEXTO));

        verify(repositorioDosFactores, org.mockito.Mockito.times(2)).registrarPasoAceptado(ID, totp.pasoActual());
    }
}
