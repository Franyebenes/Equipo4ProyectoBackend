package com.esibuy.esibuy_backend.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.esibuy.esibuy_backend.excepcion.CodigoError;
import com.esibuy.esibuy_backend.excepcion.DatosRegistroInvalidosException;
import com.esibuy.esibuy_backend.excepcion.ServicioNoDisponibleException;
import com.esibuy.esibuy_backend.modelo.DosFactores;
import com.esibuy.esibuy_backend.seguridad.PendienteRegistroDosFactores;
import com.esibuy.esibuy_backend.util.PropiedadesDosFactoresPrueba;

/**
 * Segundo factor de una cuenta que se esta registrando (HdU 2FA en el registro, fase B). Casos: CP-2FA-442 a CP-2FA-456.
 *
 * AltaDosFactores hace dos cosas, con el cifrador, el generador TOTP y el de codigos de recuperacion reales:
 *  - verificar: descifra el secreto pendiente de la sesion (ligado al correo) y comprueba el primer codigo. Acepta el paso
 *    actual y uno a cada lado (ventana de +-1, 30 s), igual que el login; cualquier otra cosa es CODIGO_INCORRECTO en el campo
 *    "codigoDosFactores". Todavia no hay cuenta, asi que no hay "ultimo aceptado" con el que comparar: devuelve el paso aceptado
 *    para dejarlo como ultimo paso de la cuenta (el mismo codigo no vale para entrar justo despues).
 *  - crear: construye el 2FA ya ACTIVO de la cuenta nueva. El secreto se vuelve a cifrar con el id de la cuenta como dato
 *    asociado (el login lo descifra asi; el del registro estaba ligado al correo), se generan los codigos de recuperacion
 *    (solo su hash se guarda) y se devuelven en claro, una sola vez.
 * Nada del secreto ni de los codigos aparece en los toString ni en los errores.
 */
class AltaDosFactoresTest {

    private static final String EMAIL = "lucia.torres@ejemplo.es";
    private static final String ID_USUARIO = "665f1c2e8a1b2c3d4e5f6a7b";
    private static final String CAMPO = "codigoDosFactores";
    private static final Instant AHORA = Instant.parse("2026-10-21T10:00:00Z");

    private final Clock reloj = Clock.fixed(AHORA, ZoneId.of("UTC"));
    private final CifradorSecretos cifrador = new CifradorSecretos(Map.of(1, PropiedadesDosFactoresPrueba.CLAVE), 1);
    private final GeneradorTotp totp = new GeneradorTotp(reloj);
    private final GeneradorCodigosRecuperacion generador = new GeneradorCodigosRecuperacion("pepper-de-prueba");

    private byte[] secreto;
    private PendienteRegistroDosFactores pendiente;
    private AltaDosFactores alta;

    @BeforeEach
    void prepararSecretoYServicio() {
        secreto = totp.nuevoSecreto();
        pendiente = new PendienteRegistroDosFactores(EMAIL, cifrador.cifrar(secreto, EMAIL), AHORA, 0);
        alta = nuevaAlta(PropiedadesDosFactoresPrueba.acordadas());
    }

    private AltaDosFactores nuevaAlta(PropiedadesDosFactores propiedades) {
        return new AltaDosFactores(cifrador, totp, generador, propiedades, reloj);
    }

    private static void esperaCodigoIncorrecto(DatosRegistroInvalidosException e) {
        assertThat(e.getErrores()).isEqualTo(Map.of(CAMPO, Set.of(CodigoError.CODIGO_INCORRECTO)));
    }

    /** Un codigo que no vale en ningun paso de la ventana, aunque por casualidad coincidiera con el de otro paso. */
    private String codigoQueNoVale() {
        List<String> validos = List.of(codigoDelPaso(-1), codigoDelPaso(0), codigoDelPaso(1));
        for (int desplazamiento = 5;; desplazamiento++) {
            String candidato = codigoDelPaso(desplazamiento);
            if (!validos.contains(candidato)) {
                return candidato;
            }
        }
    }

    private String codigoDelPaso(int desplazamiento) {
        return totp.codigo(secreto, totp.pasoActual() + desplazamiento);
    }

    // ------------------------------------------------------------------ verificar

    @Test
    void verificar_conElCodigoDelPasoActual_devuelveElPasoAceptadoYElSecreto() { // CP-2FA-442
        AltaDosFactores.Verificado verificado = alta.verificar(pendiente, codigoDelPaso(0));

        assertThat(verificado.paso()).isEqualTo(totp.pasoActual());
        assertThat(verificado.secreto()).isEqualTo(secreto);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 1})
    void verificar_aceptaElPasoAnteriorYElSiguiente(int desplazamiento) { // CP-2FA-443
        AltaDosFactores.Verificado verificado = alta.verificar(pendiente, codigoDelPaso(desplazamiento));

        assertThat(verificado.paso()).isEqualTo(totp.pasoActual() + desplazamiento);
    }

    @ParameterizedTest
    @ValueSource(ints = {-2, 2, 10})
    void verificar_rechazaUnCodigoFueraDeLaVentana(int desplazamiento) { // CP-2FA-444
        String codigo = codigoDelPaso(desplazamiento);
        // Solo vale la prueba si el codigo de ese paso no coincide por casualidad con uno de la ventana
        if (List.of(codigoDelPaso(-1), codigoDelPaso(0), codigoDelPaso(1)).contains(codigo)) {
            codigo = codigoQueNoVale();
        }
        String codigoFinal = codigo;

        DatosRegistroInvalidosException e = assertThrows(DatosRegistroInvalidosException.class,
                () -> alta.verificar(pendiente, codigoFinal));

        esperaCodigoIncorrecto(e);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "abcdef", "12345", "1234567", "ABCD-EFGH-IJKL-MNOP", "123456; DROP"})
    void verificar_conUnCodigoMalFormado_esCodigoIncorrecto(String codigo) { // CP-2FA-445
        DatosRegistroInvalidosException e = assertThrows(DatosRegistroInvalidosException.class,
                () -> alta.verificar(pendiente, codigo));

        esperaCodigoIncorrecto(e);
    }

    @Test
    void verificar_elErrorNoRepiteElCodigoNiElSecreto() { // CP-2FA-446
        String codigo = codigoQueNoVale();

        DatosRegistroInvalidosException e = assertThrows(DatosRegistroInvalidosException.class,
                () -> alta.verificar(pendiente, codigo));

        assertThat(e.getMessage() + e.getErrores()).doesNotContain(codigo, Base32.codificar(secreto), EMAIL);
        assertThat(e.getCause()).isNull();
    }

    @Test
    void verificar_conUnSecretoQueNoSeDescifraConElCorreoDelPendiente_fallaCerradoConUn503() { // CP-2FA-447
        // Cifrado para otro correo: en la practica solo ocurriria si alguien manipulase la sesion
        PendienteRegistroDosFactores manipulado = new PendienteRegistroDosFactores(EMAIL,
                cifrador.cifrar(secreto, "otro@ejemplo.es"), AHORA, 0);
        String codigo = codigoDelPaso(0);

        ServicioNoDisponibleException e = assertThrows(ServicioNoDisponibleException.class,
                () -> alta.verificar(manipulado, codigo));

        assertThat(e.getMessage()).doesNotContain(codigo, Base32.codificar(secreto), EMAIL);
    }

    @Test
    void verificado_suToStringNoIncluyeElSecreto() { // CP-2FA-448
        AltaDosFactores.Verificado verificado = alta.verificar(pendiente, codigoDelPaso(0));

        assertThat(verificado.toString()).doesNotContain(Base32.codificar(secreto)).doesNotContain(String.valueOf(verificado.paso()));
    }

    // ------------------------------------------------------------------ crear

    @Test
    void crear_devuelveUnDosFactoresActivoConElUltimoPasoYLaFechaDeAlta() { // CP-2FA-449
        AltaDosFactores.Verificado verificado = alta.verificar(pendiente, codigoDelPaso(1));

        DosFactores dosFactores = alta.crear(verificado, ID_USUARIO).dosFactores();

        assertThat(dosFactores.isActivo()).isTrue();
        assertThat(dosFactores.getPendiente()).isNull();
        assertThat(dosFactores.getActivadoEn()).isEqualTo(AHORA);
        assertThat(dosFactores.getUltimoPaso()).isEqualTo(totp.pasoActual() + 1);
    }

    @Test
    void crear_vuelveACifrarElSecretoConElIdDeLaCuentaComoDatoAsociado() { // CP-2FA-450
        AltaDosFactores.Verificado verificado = alta.verificar(pendiente, codigoDelPaso(0));

        DosFactores dosFactores = alta.crear(verificado, ID_USUARIO).dosFactores();

        assertThat(dosFactores.getSecretoCifrado()).matches("^1:[A-Za-z0-9_-]+$").doesNotContain(Base32.codificar(secreto));
        // Es lo que hace el login: descifrar con el id de la cuenta
        assertThat(cifrador.descifrar(dosFactores.getSecretoCifrado(), ID_USUARIO)).isEqualTo(secreto);
        // y ya no es el texto cifrado que estaba ligado al correo
        assertThat(dosFactores.getSecretoCifrado()).isNotEqualTo(pendiente.secretoCifrado());
        assertThrows(IllegalArgumentException.class, () -> cifrador.descifrar(dosFactores.getSecretoCifrado(), EMAIL));
    }

    @Test
    void crear_generaLosCodigosDeRecuperacionConfiguradosYGuardaSoloSuHash() { // CP-2FA-451
        AltaDosFactores.DosFactoresNuevos nuevos = alta.crear(alta.verificar(pendiente, codigoDelPaso(0)), ID_USUARIO);

        List<String> enClaro = nuevos.codigosRecuperacion();
        assertThat(enClaro).hasSize(10).doesNotHaveDuplicates()
                .allSatisfy(codigo -> assertThat(codigo).matches("^[A-Z2-7]{4}(-[A-Z2-7]{4}){3}$"));
        List<DosFactores.CodigoRecuperacion> guardados = nuevos.dosFactores().getCodigosRecuperacion();
        assertThat(guardados).hasSize(10).noneMatch(DosFactores.CodigoRecuperacion::isUsado);
        // Lo guardado es el hash de cada codigo (normalizado), y ningun codigo en claro
        assertThat(guardados.stream().map(DosFactores.CodigoRecuperacion::getHash).toList())
                .containsExactlyInAnyOrderElementsOf(enClaro.stream()
                        .map(codigo -> generador.hash(GeneradorCodigosRecuperacion.normalizar(codigo))).toList());
        assertThat(guardados).noneMatch(codigo -> enClaro.contains(codigo.getHash()));
        assertThat(nuevos.dosFactores().codigosDisponibles()).isEqualTo(10);
    }

    @Test
    void crear_laCantidadDeCodigosSalDeLaConfiguracion() { // CP-2FA-452
        AltaDosFactores conTres = nuevaAlta(PropiedadesDosFactoresPrueba.con(10, 30, 3));

        AltaDosFactores.DosFactoresNuevos nuevos = conTres.crear(conTres.verificar(pendiente, codigoDelPaso(0)), ID_USUARIO);

        assertThat(nuevos.codigosRecuperacion()).hasSize(3);
        assertThat(nuevos.dosFactores().getCodigosRecuperacion()).hasSize(3);
    }

    @Test
    void crear_cadaLlamadaGeneraCodigosDistintos() { // CP-2FA-453
        AltaDosFactores.Verificado verificado = alta.verificar(pendiente, codigoDelPaso(0));

        List<String> primeros = alta.crear(verificado, ID_USUARIO).codigosRecuperacion();
        List<String> segundos = alta.crear(verificado, ID_USUARIO).codigosRecuperacion();

        assertThat(primeros).doesNotContainAnyElementsOf(segundos);
    }

    @Test
    void crear_ningunToStringFiltraElSecretoNiLosCodigos() { // CP-2FA-454
        AltaDosFactores.DosFactoresNuevos nuevos = alta.crear(alta.verificar(pendiente, codigoDelPaso(0)), ID_USUARIO);

        String texto = nuevos + " " + nuevos.dosFactores();

        assertThat(texto).doesNotContain(Base32.codificar(secreto), nuevos.dosFactores().getSecretoCifrado());
        nuevos.codigosRecuperacion().forEach(codigo -> assertThat(texto).doesNotContain(codigo));
    }

    @Test
    void verificar_toleraUnEspacioEnElCodigoComoLoMuestraLaAplicacion() { // CP-2FA-455
        String codigo = codigoDelPaso(0);
        String conEspacio = codigo.substring(0, 3) + " " + codigo.substring(3);

        assertThat(alta.verificar(pendiente, conEspacio).paso()).isEqualTo(totp.pasoActual());
    }

    @Test
    void crear_conUnIdVacio_noCreaNada() { // CP-2FA-456
        AltaDosFactores.Verificado verificado = alta.verificar(pendiente, codigoDelPaso(0));

        assertThrows(IllegalArgumentException.class, () -> alta.crear(verificado, ""));
        assertThrows(IllegalArgumentException.class, () -> alta.crear(verificado, null));
    }
}
